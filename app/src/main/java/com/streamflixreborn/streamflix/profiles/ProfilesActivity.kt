package com.streamflixreborn.streamflix.profiles

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.BrandAssets
import kotlinx.coroutines.launch

/**
 * "¿Quién está viendo?" al estilo Netflix: perfil principal (no se puede eliminar), perfiles
 * adicionales y el recuadro "+" hasta el límite de la cuenta. Permite editar nombre, avatar
 * (solo los que subió el administrador o el dueño), PIN de 4 dígitos y eliminar.
 * Vertical en celular (cuadrícula de 2) y horizontal en TV (una fila, control remoto).
 */
class ProfilesActivity : AppCompatActivity() {

    companion object {
        /** Abre la pantalla de perfiles; al elegir uno se entra a la app. */
        /** [fromApp]: abierto desde el logo del inicio; Atrás vuelve a la app en vez de cerrarla. */
        fun start(context: Context, fromApp: Boolean = false) {
            val intent = Intent(context, ProfilesActivity::class.java).putExtra(EXTRA_FROM_APP, fromApp)
            if (!fromApp) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
        }

        private const val EXTRA_FROM_APP = "from_app"

        /** Versión TV (horizontal) o celular (vertical): la fija la compilación o, si no, el equipo. */
        fun isTvLayout(context: Context): Boolean = when (BuildConfig.APP_LAYOUT) {
            "tv" -> true
            "mobile" -> false
            else -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }

        fun openMain(context: Context) {
            context.startActivity(
                Intent(context, if (isTvLayout(context)) MainTvActivity::class.java else MainMobileActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        }
    }

    private val isTv by lazy { packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    private var account: ProfileManager.Account? = null
    private var manageMode = false

    private lateinit var title: TextView
    private lateinit var grid: GridLayout
    private lateinit var progress: ProgressBar
    private lateinit var manageButton: TextView
    private lateinit var errorBox: LinearLayout

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(buildContent())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (manageMode) setManageMode(false)
                else if (intent.getBooleanExtra(EXTRA_FROM_APP, false)) finish()
                else finishAffinity()
            }
        })

        account = ProfileManager.cachedAccount(this)
        account?.let(::render)
        reload()
    }

    // ---------------------------------------------------------------------------------------
    // Vista principal
    // ---------------------------------------------------------------------------------------

    private fun buildContent(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#141414")) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(if (isTv) 32 else 48), dp(24), dp(24))
        }

        ImageView(this).also { logo ->
            logo.adjustViewBounds = true
            if (BrandAssets.load(logo, BrandAssets.Kind.LOGO) { it.fitCenter() }) {
                column.addView(logo, LinearLayout.LayoutParams(dp(if (isTv) 160 else 140), dp(if (isTv) 56 else 48)).apply {
                    bottomMargin = dp(if (isTv) 16 else 32)
                })
            }
        }

        title = TextView(this).apply {
            text = getString(R.string.profiles_who_watching)
            setTextColor(Color.WHITE)
            textSize = if (isTv) 34f else 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        column.addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(if (isTv) 28 else 32)
        })

        progress = ProgressBar(this).apply { isIndeterminate = true }
        column.addView(progress, LinearLayout.LayoutParams(dp(40), dp(40)))

        grid = GridLayout(this).apply {
            columnCount = if (isTv) 6 else 2
            useDefaultMargins = false
            clipChildren = false
            clipToPadding = false
        }
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        errorBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(TextView(context).apply {
                text = getString(R.string.profiles_error)
                setTextColor(Color.parseColor("#B3B3B3"))
                gravity = Gravity.CENTER
                textSize = 15f
            })
            addView(outlinedButton(getString(R.string.profiles_retry)) { reload() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(16) })
        }
        column.addView(errorBox)

        manageButton = outlinedButton(getString(R.string.profiles_manage)) { setManageMode(!manageMode) }
        column.addView(manageButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(if (isTv) 36 else 40)
        })

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // En TV el contenido queda centrado verticalmente, como en Netflix.
        column.gravity = Gravity.CENTER_HORIZONTAL or if (isTv) Gravity.CENTER_VERTICAL else Gravity.TOP
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    private fun reload() {
        progress.visibility = if (account == null) View.VISIBLE else View.GONE
        errorBox.visibility = View.GONE
        lifecycleScope.launch {
            runCatching { ProfileManager.load(this@ProfilesActivity) }
                .onSuccess { loaded ->
                    account = loaded
                    // Cuenta de 1 dispositivo: entra directo con el principal.
                    if (loaded.limit <= 1 && loaded.profiles.size <= 1) {
                        enter(loaded.profiles.firstOrNull() ?: ProfileManager.Profile(ProfileManager.MAIN_ID, "", "", ""))
                        return@launch
                    }
                    render(loaded)
                }
                .onFailure {
                    progress.visibility = View.GONE
                    if (account == null) {
                        // Sin conexión y sin perfiles guardados: se entra con el principal.
                        if (ProfileManager.limit(this@ProfilesActivity) <= 1) {
                            enter(ProfileManager.Profile(ProfileManager.MAIN_ID, "", "", ""))
                        } else {
                            // Se puede entrar al principal; el resto aparece al volver la conexión.
                            render(ProfileManager.Account(1, listOf(ProfileManager.Profile(ProfileManager.MAIN_ID, "", "", "")), emptyList()))
                            errorBox.visibility = View.VISIBLE
                            manageButton.visibility = View.GONE
                        }
                    }
                }
        }
    }

    private fun setManageMode(enabled: Boolean) {
        manageMode = enabled
        title.text = getString(if (enabled) R.string.profiles_manage else R.string.profiles_who_watching)
        manageButton.text = getString(if (enabled) R.string.profiles_done else R.string.profiles_manage)
        account?.let(::render)
    }

    private fun render(data: ProfileManager.Account) {
        progress.visibility = View.GONE
        errorBox.visibility = View.GONE
        grid.removeAllViews()
        val tileSize = dp(if (isTv) 130 else 112)
        data.profiles.forEach { profile ->
            grid.addView(profileTile(profile, tileSize))
        }
        if (data.profiles.size < data.limit) grid.addView(addTile(tileSize))
        manageButton.visibility = if (data.profiles.isEmpty()) View.GONE else View.VISIBLE

        // TV: el foco empieza en el perfil activo.
        if (isTv) {
            val index = data.profiles.indexOfFirst { it.id == ProfileManager.activeId }.coerceAtLeast(0)
            grid.getChildAt(index)?.post { grid.getChildAt(index)?.requestFocus() }
        }
    }

    private fun profileTile(profile: ProfileManager.Profile, size: Int): View {
        val tile = tileContainer(size)
        val frame = FrameLayout(this)
        val avatar = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.parseColor("#333333"), dp(6))
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        Glide.with(this).load(profile.avatar)
            .transform(CenterCrop(), RoundedCorners(dp(6)))
            .error(R.mipmap.ic_launcher)
            .into(avatar)
        frame.addView(avatar, FrameLayout.LayoutParams(size, size))

        if (manageMode) {
            frame.addView(View(this).apply { background = rounded(Color.parseColor("#99000000"), dp(6)) }, FrameLayout.LayoutParams(size, size))
            frame.addView(TextView(this).apply {
                text = "✎"
                textSize = if (isTv) 40f else 34f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(size, size))
        } else if (profile.hasPin) {
            frame.addView(TextView(this).apply {
                text = "🔒"
                textSize = 16f
                gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(4), dp(4)) })
        }
        tile.addView(frame)

        tile.addView(tileLabel(profile.name.ifBlank { getString(R.string.profiles_main_badge) }, size))
        if (profile.isMain) tile.addView(tileSub(getString(R.string.profiles_main_badge)))

        tile.setOnClickListener {
            if (manageMode) openEditor(profile) else askPinThen(profile) { enter(profile) }
        }
        bindFocus(tile, avatar)
        return tile
    }

    private fun addTile(size: Int): View {
        val tile = tileContainer(size)
        val box = TextView(this).apply {
            text = "+"
            textSize = if (isTv) 64f else 54f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#808080"))
            background = rounded(Color.TRANSPARENT, dp(6), Color.parseColor("#808080"), dp(2))
        }
        tile.addView(box, LinearLayout.LayoutParams(size, size))
        tile.addView(tileLabel(getString(R.string.profiles_add), size))
        tile.setOnClickListener { openEditor(null) }
        bindFocus(tile, box)
        return tile
    }

    private fun tileContainer(size: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isFocusable = true
        isClickable = true
        val margin = dp(if (isTv) 14 else 12)
        layoutParams = GridLayout.LayoutParams().apply { setMargins(margin, margin, margin, margin); width = size + dp(8) }
    }

    private fun tileLabel(text: String, size: Int) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#B3B3B3"))
        textSize = if (isTv) 17f else 15f
        gravity = Gravity.CENTER
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    }

    private fun tileSub(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#737373"))
        textSize = 11f
        gravity = Gravity.CENTER
    }

    /** TV: el perfil enfocado crece y se marca con borde blanco (como en Netflix). */
    private fun bindFocus(tile: View, image: View) {
        tile.setOnFocusChangeListener { _, focused ->
            val scale = if (focused) 1.1f else 1f
            tile.animate().scaleX(scale).scaleY(scale).setDuration(150).start()
            // Borde blanco al enfocar (View.foreground no existe en Android 5).
            if (image.getTag(R.id.profiles_tag_background) == null) image.setTag(R.id.profiles_tag_background, image.background ?: rounded(Color.TRANSPARENT, dp(6)))
            image.background = if (focused) rounded(Color.WHITE, dp(8)) else image.getTag(R.id.profiles_tag_background) as android.graphics.drawable.Drawable
            ((tile as ViewGroup).getChildAt(1) as? TextView)?.setTextColor(Color.parseColor(if (focused) "#FFFFFF" else "#B3B3B3"))
        }
    }

    // ---------------------------------------------------------------------------------------
    // Entrar / PIN
    // ---------------------------------------------------------------------------------------

    private fun enter(profile: ProfileManager.Profile) {
        lifecycleScope.launch {
            ProfileManager.switchTo(this@ProfilesActivity, profile.id)
            CloudSyncManager.initialize(this@ProfilesActivity)
            openMain(this@ProfilesActivity)
            finish()
        }
    }

    private fun askPinThen(profile: ProfileManager.Profile, onOk: () -> Unit) {
        if (!profile.hasPin) return onOk()
        pinDialog(getString(R.string.profiles_pin_enter)) { pin ->
            if (pin == profile.pin) {
                onOk(); true
            } else {
                Toast.makeText(this, R.string.profiles_pin_wrong, Toast.LENGTH_SHORT).show(); false
            }
        }
    }

    /** Diálogo de 4 dígitos. [onPin] devuelve true para cerrarlo. */
    private fun pinDialog(message: String, onPin: (String) -> Boolean) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            gravity = Gravity.CENTER
            textSize = 30f
            letterSpacing = 0.6f
            setTextColor(Color.WHITE)
            background = rounded(Color.parseColor("#333333"), dp(6))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
            addView(TextView(context).apply {
                text = message
                setTextColor(Color.parseColor("#E5E5E5"))
                textSize = 16f
                gravity = Gravity.CENTER
            })
            addView(input, LinearLayout.LayoutParams(dp(200), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(16)
            })
        }
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setView(box)
            .setNegativeButton(R.string.profiles_cancel, null)
            .create()
        fun submit() {
            val pin = input.text.toString()
            if (pin.length == 4) {
                if (onPin(pin)) dialog.dismiss() else input.setText("")
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { if (s?.length == 4) submit() }
        })
        input.setOnEditorActionListener { _, _, _ -> submit(); true }
        dialog.setOnShowListener {
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
        dialog.show()
    }

    // ---------------------------------------------------------------------------------------
    // Editar / agregar
    // ---------------------------------------------------------------------------------------

    private fun openEditor(profile: ProfileManager.Profile?) {
        val data = account ?: return
        if (profile == null) {
            showEditor(null, data)
        } else {
            // Para editar un perfil con PIN se pide el PIN primero.
            askPinThen(profile) { showEditor(profile, data) }
        }
    }

    private fun showEditor(profile: ProfileManager.Profile?, data: ProfileManager.Account) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        var avatar = profile?.avatar ?: data.avatars.firstOrNull().orEmpty()
        var pin = profile?.pin.orEmpty()
        val nextId = (1..4).map { "%02d".format(it) }.firstOrNull { id -> data.profiles.none { it.id == id } } ?: "01"
        val username = data.profiles.firstOrNull { it.isMain }?.name ?: ""

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#141414"))
            setPadding(dp(24), dp(if (isTv) 28 else 40), dp(24), dp(24))
        }
        root.addView(TextView(this).apply {
            text = getString(if (profile == null) R.string.profiles_add else R.string.profiles_edit)
            setTextColor(Color.WHITE)
            textSize = if (isTv) 30f else 26f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(View(this).apply { setBackgroundColor(Color.parseColor("#333333")) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { setMargins(0, dp(12), 0, dp(20)) })

        // Celular: columna. TV: avatar a la izquierda y el formulario a la derecha.
        val body = LinearLayout(this).apply { orientation = if (isTv) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val bigAvatar = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        fun showAvatar() {
            Glide.with(this).load(avatar).transform(CenterCrop(), RoundedCorners(dp(6))).error(R.mipmap.ic_launcher).into(bigAvatar)
        }
        showAvatar()
        body.addView(bigAvatar, LinearLayout.LayoutParams(dp(if (isTv) 140 else 120), dp(if (isTv) 140 else 120)).apply {
            gravity = if (isTv) Gravity.TOP else Gravity.CENTER_HORIZONTAL
            if (isTv) marginEnd = dp(28) else bottomMargin = dp(20)
        })

        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val nameInput = EditText(this).apply {
            setText(profile?.name ?: if (username.isNotEmpty()) "$username-$nextId" else "")
            hint = getString(R.string.profiles_name_hint)
            setHintTextColor(Color.parseColor("#808080"))
            setTextColor(Color.WHITE)
            textSize = 18f
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(20))
            background = rounded(Color.parseColor("#333333"), dp(4))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        form.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Avatares disponibles (solo los del administrador / dueño).
        form.addView(sectionTitle(getString(R.string.profiles_choose_avatar)))
        val avatarRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        data.avatars.forEach { url ->
            val item = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                isFocusable = true
                isClickable = true
                setPadding(dp(3), dp(3), dp(3), dp(3))
            }
            fun mark() { item.background = if (url == avatar) rounded(Color.TRANSPARENT, dp(6), Color.WHITE, dp(3)) else null }
            mark()
            Glide.with(this).load(url).transform(CenterCrop(), RoundedCorners(dp(6))).into(item)
            item.setOnClickListener {
                avatar = url
                showAvatar()
                for (i in 0 until avatarRow.childCount) avatarRow.getChildAt(i).background = null
                mark()
            }
            item.setOnFocusChangeListener { v, f -> v.animate().scaleX(if (f) 1.12f else 1f).scaleY(if (f) 1.12f else 1f).setDuration(120).start() }
            avatarRow.addView(item, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(10) })
        }
        form.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(4), dp(6), dp(4), dp(6))
            addView(avatarRow)
        })

        // PIN de 4 dígitos.
        form.addView(sectionTitle(getString(R.string.profiles_pin_title)))
        val pinStatus = TextView(this).apply { setTextColor(Color.parseColor("#B3B3B3")); textSize = 14f }
        val pinButton = outlinedButton("") {}
        fun showPin() {
            pinStatus.text = getString(if (pin.length == 4) R.string.profiles_pin_on else R.string.profiles_pin_off)
            pinButton.text = getString(if (pin.length == 4) R.string.profiles_pin_remove else R.string.profiles_pin_set)
        }
        showPin()
        pinButton.setOnClickListener {
            if (pin.length == 4) {
                pin = ""; showPin()
            } else {
                pinDialog(getString(R.string.profiles_pin_new)) { newPin -> pin = newPin; showPin(); true }
            }
        }
        form.addView(pinStatus)
        form.addView(pinButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        // Botones.
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val save = solidButton(getString(R.string.profiles_save))
        actions.addView(save)
        actions.addView(outlinedButton(getString(R.string.profiles_cancel)) { dialog.dismiss() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        if (profile != null && !profile.isMain) {
            actions.addView(outlinedButton(getString(R.string.profiles_delete)) {
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setMessage(getString(R.string.profiles_delete_confirm, profile.name))
                    .setNegativeButton(R.string.profiles_cancel, null)
                    .setPositiveButton(R.string.profiles_delete) { _, _ ->
                        runApi(dialog) { ProfileManager.delete(this@ProfilesActivity, profile.id).also { deleteLocal(profile.id) } }
                    }
                    .show()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        form.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })

        save.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isEmpty()) {
                nameInput.error = getString(R.string.profiles_name_hint)
                return@setOnClickListener
            }
            runApi(dialog) {
                if (profile == null) {
                    val created = ProfileManager.add(this@ProfilesActivity, name, avatar.ifEmpty { null })
                    val newId = created.profiles.map { it.id }.firstOrNull { id -> data.profiles.none { it.id == id } }
                    if (newId != null && pin.length == 4) ProfileManager.edit(this@ProfilesActivity, newId, pin = pin) else created
                } else {
                    ProfileManager.edit(this@ProfilesActivity, profile.id, name = name, avatar = avatar.ifEmpty { null }, pin = pin)
                }
            }
        }

        body.addView(form, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            if (!isTv) width = ViewGroup.LayoutParams.MATCH_PARENT
        })
        root.addView(body)
        dialog.setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#141414"))
            addView(root)
        })
        dialog.setOnShowListener { (if (isTv) save else nameInput).requestFocus() }
        dialog.show()
    }

    /** Guarda en el servidor y actualiza la lista. */
    private fun runApi(dialog: Dialog, block: suspend () -> ProfileManager.Account) {
        lifecycleScope.launch {
            runCatching { block() }
                .onSuccess {
                    account = it
                    dialog.dismiss()
                    render(it)
                }
                .onFailure {
                    val msg = if ((it as? ProfileManager.ApiException)?.code == "limit") R.string.profiles_limit_reached else R.string.profiles_save_error
                    Toast.makeText(this@ProfilesActivity, msg, Toast.LENGTH_LONG).show()
                }
        }
    }

    /** Al eliminar un perfil se borran también sus datos en este equipo. */
    private fun deleteLocal(id: String) {
        val suffix = "_p$id"
        databaseList().filter { it.substringBefore(".db").endsWith(suffix) }.forEach { deleteDatabase(it) }
        listOf("user-data-cache", "home-cache").forEach { dir ->
            java.io.File(filesDir, dir).listFiles()?.filter { it.name.endsWith("$suffix.json") }?.forEach { it.delete() }
        }
        com.streamflixreborn.streamflix.utils.UserDataCache.clearMemory()
        com.streamflixreborn.streamflix.utils.HomeCacheStore.clearMemory()
        if (ProfileManager.activeId == id) ProfileManager.resetToMain(this)
    }

    // ---------------------------------------------------------------------------------------
    // Utilidades de vista
    // ---------------------------------------------------------------------------------------

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(20), 0, dp(6))
    }

    private fun outlinedButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#B3B3B3"))
        textSize = 15f
        letterSpacing = 0.08f
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        setPadding(dp(22), dp(10), dp(22), dp(10))
        background = rounded(Color.TRANSPARENT, dp(2), Color.parseColor("#808080"), dp(1))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { v, f ->
            (v as TextView).setTextColor(if (f) Color.BLACK else Color.parseColor("#B3B3B3"))
            v.background = if (f) rounded(Color.WHITE, dp(2)) else rounded(Color.TRANSPARENT, dp(2), Color.parseColor("#808080"), dp(1))
        }
    }

    private fun solidButton(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(Color.BLACK)
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        setPadding(dp(26), dp(10), dp(26), dp(10))
        background = rounded(Color.WHITE, dp(2))
        setOnFocusChangeListener { v, f -> v.background = rounded(if (f) Color.parseColor("#E50914") else Color.WHITE, dp(2)); (v as TextView).setTextColor(if (f) Color.WHITE else Color.BLACK) }
    }

    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (strokeColor != null) setStroke(strokeWidth, strokeColor)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
