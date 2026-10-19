' Llamadas de red. Se ejecutan SOLO dentro de ApiTask (hilo de tarea), nunca en la interfaz.
' Cada función equivale a una parte de la app Android (indicada en su comentario).

function BrowserUA() as string
    return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
end function

function UrlEnc(text as string) as string
    return CreateObject("roUrlTransfer").Escape(text)
end function

function NewTransfer(url as string, headers as dynamic) as object
    xfer = CreateObject("roUrlTransfer")
    xfer.SetUrl(url)
    xfer.SetCertificatesFile("common:/certs/ca-bundle.crt")
    xfer.InitClientCertificates()
    xfer.EnableEncodings(true)
    xfer.RetainBodyOnError(true)
    if headers = invalid or headers["User-Agent"] = invalid then xfer.AddHeader("User-Agent", BrowserUA())
    if headers <> invalid then
        for each name in headers
            xfer.AddHeader(name, AsStr(headers[name]))
        end for
    end if
    return xfer
end function

' opts: { method, body, headers, timeout (ms) } -> { code, body }
function Http(url as string, opts = {} as object) as object
    port = CreateObject("roMessagePort")
    xfer = NewTransfer(url, opts.headers)
    xfer.SetMessagePort(port)
    timeout = 15000
    if opts.timeout <> invalid then timeout = opts.timeout
    method = "GET"
    if opts.method <> invalid then method = opts.method
    if method = "GET" then
        started = xfer.AsyncGetToString()
    else
        xfer.SetRequest(method)
        started = xfer.AsyncPostFromString(AsStr(opts.body))
    end if
    if not started then return { code: -1, body: "" }
    msg = wait(timeout, port)
    if type(msg) = "roUrlEvent" then return { code: msg.GetResponseCode(), body: msg.GetString() }
    xfer.AsyncCancel()
    return { code: -1, body: "" }
end function

' Varias peticiones GET a la vez (filas del inicio, listas M3U). Devuelve los cuerpos en orden.
function HttpParallel(urls as object, headers = invalid as dynamic, timeout = 20000 as integer) as object
    port = CreateObject("roMessagePort")
    results = []
    pending = {}
    for i = 0 to urls.Count() - 1
        results.Push({ code: -1, body: "" })
        xfer = NewTransfer(urls[i], headers)
        xfer.SetMessagePort(port)
        if xfer.AsyncGetToString() then pending[xfer.GetIdentity().ToStr()] = { index: i, xfer: xfer }
    end for
    deadline = CreateObject("roTimespan")
    while pending.Count() > 0
        remaining = timeout - deadline.TotalMilliseconds()
        if remaining <= 0 then exit while
        msg = wait(remaining, port)
        if type(msg) = "roUrlEvent" then
            id = msg.GetSourceIdentity().ToStr()
            entry = pending[id]
            if entry <> invalid then
                results[entry.index] = { code: msg.GetResponseCode(), body: msg.GetString() }
                pending.Delete(id)
            end if
        else if msg = invalid then
            exit while
        end if
    end while
    for each id in pending
        pending[id].xfer.AsyncCancel()
    end for
    return results
end function

function JsonOf(body as string) as dynamic
    if body = invalid or body.Trim() = "" then return invalid
    return ParseJson(body)
end function

' Usuario y contraseña en cabeceras: el panel verifica que la nube/perfiles son de esa cuenta.
function SyncHeaders() as object
    return { "X-Sync-User": UrlEnc(RegRead("alpha_token")), "X-Sync-Pass": UrlEnc(RegRead("beta_token")) }
end function

function ActiveUserId() as string
    return SafeName(RegRead("alpha_token"))
end function

' Kotlin substringAfter/substringBefore con valor vacío si no aparece.
function SubAfter(text as string, delim as string) as string
    i = Instr(1, text, delim)
    if i = 0 then return ""
    return Mid(text, i + Len(delim))
end function

function SubBefore(text as string, delim as string) as string
    i = Instr(1, text, delim)
    if i = 0 then return ""
    return Left(text, i - 1)
end function

' =======================================================================================
' Inicio de sesión (SyncGatewayActivity.executeGatewayHandshake)
' =======================================================================================

function ApiLogin(p as object) as object
    user = AsStr(p.user).Trim()
    pass = AsStr(p.pass).Trim()
    mac = DeviceMac()
    url = PanelApiUrl("flixlat_api.php") + "?username=" + UrlEnc(user) + "&password=" + UrlEnc(pass) + "&mac=" + UrlEnc(mac) + "&token=" + UrlEnc(SecurityToken(user, mac))
    r = Http(url, { timeout: 10000 })
    if r.code <> 200 then
        ' Sin servidor: con sesión guardada se entra con lo que ya había (Android: enterWithCachedSession).
        hasCache = RegRead("assigned_servers") <> "" or RegRead("panel_channel_lines") <> "" or RegRead("personal_provider_url") <> ""
        if p.auto = true and hasCache then return { ok: true, cached: true }
        if r.code > 0 then return { ok: false, message: "No se pudo conectar con el servidor (HTTP " + r.code.ToStr() + ").", report: "Login.Servidor", detail: "HTTP " + r.code.ToStr() }
        return { ok: false, message: "Error de conexión. Revisa tu internet e inténtalo de nuevo.", report: "Login.Conexion", detail: "Sin respuesta del servidor" }
    end if

    json = JsonOf(r.body)
    if json = invalid or json.user_info = invalid or ToInt(json.user_info.auth) <> 1 then
        message = "Acceso denegado."
        if json <> invalid and json.user_info <> invalid and PanelText(json.user_info.message) <> "" then message = PanelText(json.user_info.message)
        if p.auto = true then RegClearSession()
        return { ok: false, message: message }
    end if

    names = []
    if GetInterface(json.assigned_servers, "ifArray") <> invalid then
        for each server in json.assigned_servers
            if server <> invalid and GetInterface(server, "ifAssociativeArray") <> invalid and PanelText(server.name) <> "" then names.Push(PanelText(server.name))
        end for
    end if
    channelLines = PanelLines(json.channel_lines)
    personal = PanelLines(json.personal_provider_url)
    if names.Count() = 0 and channelLines.Count() = 0 and personal.Count() = 0 then
        if p.auto = true then RegClearSession()
        return { ok: false, message: "Tu cuenta no tiene servidores asignados. Contacta a tu distribuidor." }
    end if

    ' Entrada por MAC (campos vacíos): el panel devuelve la cuenta vinculada.
    accountName = PanelText(json.user_info.username)
    if accountName = "" then accountName = user
    accountPass = pass
    if accountPass = "" then accountPass = PanelText(json.user_info.password)
    savedUser = user
    if savedUser = "" then savedUser = accountName

    limit = ToInt(json.profile_limit, 1)
    if limit < 1 then limit = 1
    if limit > 5 then limit = 5

    RegWriteMany({
        alpha_token: savedUser
        beta_token: accountPass
        custom_logo: PanelText(json.custom_logo)
        custom_background_url: PanelText(json.custom_background)
        custom_background_tv_url: PanelText(json.custom_background_tv)
        custom_splash_url: PanelText(json.custom_splash)
        tmdb_api_key: PanelText(json.tmdb_api_key)
        app_id: ToInt(json.app_id).ToStr()
        assigned_servers: names.Join(",")
        brand_name: PanelText(json.app_name)
        default_provider: PanelText(json.default_provider)
        panel_channel_lines: channelLines.Join(Chr(10))
        personal_provider_url: personal.Join(Chr(10))
        panel_default_channel_line: PanelText(json.default_channel_line)
        channel_lines_from: ToInt(json.channel_lines_from).ToStr()
        settings_note: PanelText(json.settings_note)
        live_enabled: AsStr(channelLines.Count() > 0 or personal.Count() > 0)
        profile_limit: limit.ToStr()
        last_online_check: NowSec().ToStr()
    })

    ' El equipo guarda datos de una sola cuenta: si entra otra, se borra lo local (ya está en su nube).
    owner = RegRead("device_owner")
    if (owner <> "" and LCase(owner) <> LCase(accountName)) or (owner = "" and p.auto <> true) then
        UD_DeleteAllFiles()
        RegWrite("active_profile", "00")
    end if
    RegWrite("device_owner", accountName)
    return { ok: true, profileLimit: limit }
end function

' =======================================================================================
' Verificación de la línea cada 30 min (StreamFlixApp.runSecurityChecks)
' =======================================================================================

function ApiSecurity() as object
    user = RegRead("alpha_token")
    pass = RegRead("beta_token")
    if user = "" or pass = "" then return { action: "none" }
    mac = DeviceMac()
    url = PanelApiUrl("check_security.php") + "?u=" + UrlEnc(user) + "&p=" + UrlEnc(pass) + "&v=" + AppVersionCode().ToStr() + "&mac=" + UrlEnc(mac) + "&token=" + UrlEnc(SecurityToken(user, mac)) + "&platform=roku"
    r = Http(url, { timeout: 6000 })
    json = invalid
    if r.code = 200 then json = JsonOf(r.body)
    if json = invalid then
        ' Sin conexión con el panel: se permite seguir hasta 7 días desde la última verificación.
        last = RegRead("last_online_check", NowSec().ToStr()).ToInt()
        if NowSec() - last > 7 * 24 * 3600 then return { action: "logout", message: "No se pudo verificar tu cuenta en los últimos 7 días. Inicia sesión de nuevo." }
        return { action: "offline" }
    end if
    authFailed = json.user_info <> invalid and ToInt(json.user_info.auth, 1) = 0
    if AsStr(json.status) = "banned" or authFailed then
        message = "Tu cuenta fue desactivada o venció."
        if json.user_info <> invalid and PanelText(json.user_info.message) <> "" then message = PanelText(json.user_info.message)
        return { action: "logout", message: message }
    end if
    ' update_required no se usa: la actualización del panel es un APK solo para Android.
    RegWrite("last_online_check", NowSec().ToStr())
    return { action: "ok" }
end function

' =======================================================================================
' Marca del panel cada 5 min (CloudSyncManager.applyPanelBranding)
' =======================================================================================

function ApiBranding() as object
    appId = RegRead("app_id", "0").ToInt()
    urls = [PanelConfigUrl("config.json") + "?cb=" + NowSec().ToStr()]
    if appId <> 0 then urls.Push(PanelConfigUrl("config_" + appId.ToStr() + ".json") + "?cb=" + NowSec().ToStr())
    results = HttpParallel(urls, invalid, 8000)
    master = invalid
    own = invalid
    if results[0].code = 200 then master = JsonOf(results[0].body)
    if appId <> 0 and results[1].code = 200 then own = JsonOf(results[1].body)
    ' Si una de las dos no se pudo leer ahora, no se cambia nada (evita alternar marcas).
    if master = invalid or (appId <> 0 and own = invalid) then return { changed: false }

    values = {
        custom_background_url: BrandField(own, master, "custom_background")
        custom_background_tv_url: BrandField(own, master, "custom_background_tv")
        custom_splash_url: BrandField(own, master, "custom_splash")
        brand_name: BrandField(own, master, "app_name")
        settings_note: BrandField(own, master, "settings_note")
        custom_logo: BrandField(own, master, "custom_logo")
    }
    ' Canales: cada administrador usa solo los suyos (0 = dueño, APP ID = administrador, -1 = ninguna).
    from = RegRead("channel_lines_from", "0").ToInt()
    if from = 0 and master.DoesExist("channel_lines") then values.panel_channel_lines = PanelLines(master.channel_lines).Join(Chr(10))
    if from <> 0 and from = appId and own <> invalid and own.DoesExist("channel_lines") then values.panel_channel_lines = PanelLines(own.channel_lines).Join(Chr(10))
    if from < 0 then values.panel_channel_lines = ""

    changed = false
    for each key in values
        if RegRead(key) <> values[key] then changed = true
    end for
    if changed then RegWriteMany(values)
    return { changed: changed }
end function

function BrandField(own as dynamic, master as dynamic, key as string) as string
    value = ""
    if own <> invalid then value = PanelText(own[key])
    if value = "" and master <> invalid then value = PanelText(master[key])
    return value
end function

' =======================================================================================
' Aviso de vencimiento (ntf_api.php). Roku no tiene navegador: se muestra el texto de la página.
' =======================================================================================

function ApiNotice() as object
    url = PanelApiUrl("ntf_api.php") + "?mac=" + UrlEnc(DeviceMac()) + "&u=" + UrlEnc(RegRead("alpha_token")) + "&cb=" + NowSec().ToStr() + "&platform=roku"
    r = Http(url, { timeout: 10000 })
    if r.code <> 200 then return { text: "" }
    ' La página es "AVISO IMPORTANTE" + el mensaje: la primera línea va como título de la tarjeta.
    lines = HtmlToText(r.body).Split(Chr(10))
    if lines.Count() < 2 then return { title: "Aviso", text: lines.Join(Chr(10)) }
    title = lines.Shift()
    return { title: title, text: lines.Join(Chr(10)) }
end function

function HtmlToText(html as string) as string
    s = CreateObject("roRegex", "<(script|style|head)[^>]*>.*?</\1>", "is").ReplaceAll(html, " ")
    s = CreateObject("roRegex", "<br\s*/?>|</p>|</div>|</h[1-6]>|</li>|</tr>", "i").ReplaceAll(s, Chr(10))
    ' Los botones de la página (Cerrar, Aceptar) no sirven en Roku: se cierra con Atrás u OK.
    s = CreateObject("roRegex", "<button[^>]*>.*?</button>", "is").ReplaceAll(s, " ")
    s = CreateObject("roRegex", "<[^>]+>", "s").ReplaceAll(s, " ")
    entities = [["&nbsp;", " "], ["&amp;", "&"], ["&quot;", Chr(34)], ["&#39;", "'"], ["&lt;", "<"], ["&gt;", ">"], ["&aacute;", "á"], ["&eacute;", "é"], ["&iacute;", "í"], ["&oacute;", "ó"], ["&uacute;", "ú"], ["&ntilde;", "ñ"], ["&iquest;", "¿"], ["&iexcl;", "¡"]]
    for each e in entities
        s = s.Replace(e[0], e[1])
    end for
    lines = []
    for each line in s.Split(Chr(10))
        t = CreateObject("roRegex", "[ \t\r]+", "").ReplaceAll(line, " ").Trim()
        if t <> "" then lines.Push(t)
    end for
    return lines.Join(Chr(10))
end function

' =======================================================================================
' Perfiles (ProfileManager -> profiles.php)
' =======================================================================================

function ApiProfiles(body as dynamic) as object
    if RegRead("alpha_token") = "" or RegRead("beta_token") = "" then return { ok: false, error: "auth" }
    opts = { headers: SyncHeaders(), timeout: 10000 }
    if body <> invalid then
        opts.method = "POST"
        opts.body = FormatJson(body)
        opts.headers["Content-Type"] = "application/json"
    end if
    r = Http(PanelApiUrl("profiles.php"), opts)
    json = JsonOf(r.body)
    if json = invalid then return { ok: false, error: "http_" + r.code.ToStr() }
    if AsStr(json.status) <> "success" then
        message = AsStr(json.message)
        if message = "" then message = "error"
        return { ok: false, error: message }
    end if
    account = ParseAccount(json)
    RegWriteMany({ profiles_cache: FormatJson(account), profile_limit: account.limit.ToStr() })
    return { ok: true, account: account }
end function

function ParseAccount(json as object) as object
    limit = ToInt(json.limit, 1)
    if limit < 1 then limit = 1
    if limit > 5 then limit = 5
    profiles = []
    if GetInterface(json.profiles, "ifArray") <> invalid then
        for each p in json.profiles
            id = AsStr(p.id)
            if id = "" then id = "00"
            profiles.Push({ id: id, name: AsStr(p.name), avatar: AsStr(p.avatar), pin: AsStr(p.pin) })
        end for
    end if
    avatars = []
    if GetInterface(json.avatars, "ifArray") <> invalid then
        for each a in json.avatars
            avatars.Push(AsStr(a))
        end for
    end if
    return { limit: limit, profiles: profiles, avatars: avatars }
end function

' =======================================================================================
' Nube del perfil (CloudSyncManager -> sync_userdata.php?u=<usuario>&p=<perfil>)
' =======================================================================================

function CloudFile(profileId as string) as string
    return "cachefs:/cloud_" + profileId + ".json"
end function

function ApiSyncDown(profileId as string) as object
    if RegRead("alpha_token") = "" then return { ok: false }
    url = PanelApiUrl("sync_userdata.php") + "?u=" + ActiveUserId() + "&p=" + profileId
    r = Http(url, { headers: SyncHeaders(), timeout: 10000 })
    if r.code <> 200 then return { ok: false }
    doc = {}
    if r.body.Trim() <> "" then
        json = JsonOf(r.body)
        if json = invalid or GetInterface(json, "ifAssociativeArray") = invalid then return { ok: false }
        if AsStr(json.status) <> "empty" then
            for each key in json
                if Left(key, 1) <> "_" and GetInterface(json[key], "ifAssociativeArray") <> invalid then doc[key] = json[key]
            end for
        end if
    end if
    ' Copia completa de la nube: al subir se conservan los proveedores que Roku no usa.
    WriteAsciiFile(CloudFile(profileId), FormatJson(doc))
    mine = doc[SafeName(CatalogProviderName())]
    if mine = invalid then mine = {}
    return { ok: true, profileId: profileId, mine: mine }
end function

function ApiSyncUp(profileId as string, data as object) as object
    ' Nunca se sube sin haber leído la nube antes: en un equipo nuevo borraría datos de otros.
    text = ReadAsciiFile(CloudFile(profileId))
    if text = "" then
        down = ApiSyncDown(profileId)
        if not down.ok then return { ok: false }
        text = ReadAsciiFile(CloudFile(profileId))
    end if
    doc = JsonOf(text)
    if doc = invalid then doc = {}
    doc[SafeName(CatalogProviderName())] = data

    url = PanelApiUrl("sync_userdata.php") + "?u=" + ActiveUserId() + "&p=" + profileId
    headers = SyncHeaders()
    headers["Content-Type"] = "application/json"
    r = Http(url, { method: "POST", body: FormatJson(doc), headers: headers, timeout: 10000 })
    ' El servidor responde 200 también en errores: se revisa "status".
    json = JsonOf(r.body)
    ok = r.code = 200 and (json = invalid or json.status = invalid or AsStr(json.status) = "success")
    if ok then WriteAsciiFile(CloudFile(profileId), FormatJson(doc))
    return { ok: ok }
end function

' =======================================================================================
' Reporte de errores al panel (ErrorReporter -> log_error.php)
' =======================================================================================

function ApiReport(p as object) as object
    body = {
        tipo: Left(AsStr(p.tipo), 120)
        mensaje: Left(AsStr(p.mensaje), 2000)
        stacktrace: ""
        dispositivo: DeviceModel()
        version_app: AppVersionName() + " (Roku)"
        app_name: "VOD Pro (Roku)"
        usuario: RegRead("alpha_token")
        proveedor: CatalogProviderName()
        app_id: RegRead("app_id", "0").ToInt()
    }
    r = Http(PanelApiUrl("log_error.php"), { method: "POST", body: FormatJson(body), headers: { "Content-Type": "application/json; charset=UTF-8" }, timeout: 8000 })
    return { ok: r.code >= 200 and r.code < 300 }
end function
