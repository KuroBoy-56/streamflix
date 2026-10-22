' Escena principal: pila de pantallas, sesión del panel (verificación cada 30 min, marca cada
' 5 min, aviso de vencimiento) y nube del perfil (MainTvActivity + StreamFlixApp en Android).

sub init()
    m.screens = m.top.findNode("screens")
    m.bgImage = m.top.findNode("bgImage")
    m.splash = m.top.findNode("splash")
    m.stack = []
    m.syncedProfile = ""
    m.sessionStarted = false
    m.lastPull = 0

    m.securityTimer = m.top.findNode("securityTimer")
    m.securityTimer.observeField("fire", "onSecurityTimer")
    m.brandingTimer = m.top.findNode("brandingTimer")
    m.brandingTimer.observeField("fire", "onBrandingTimer")
    m.uploadTimer = m.top.findNode("uploadTimer")
    m.uploadTimer.observeField("fire", "onUploadTimer")
    m.noticeTimer = m.top.findNode("noticeTimer")
    m.noticeTimer.observeField("fire", "onNoticeTimer")
    m.splashTimer = m.top.findNode("splashTimer")
    m.splashTimer.observeField("fire", "onSplashTimer")
    m.splashImage = m.top.findNode("splashImage")
    m.splashImage.observeField("loadStatus", "onSplashLoad")
    m.global.observeField("userDataTick", "onUserDataChanged")

    ApplyBranding()
    ReplaceAll("LoginScreen", {})
end sub

' ---------------------------------------------------------------------------------------
' Pila de pantallas
' ---------------------------------------------------------------------------------------

sub PushScreen(name as string, params as object)
    screen = CreateObject("roSGNode", name)
    if screen = invalid then return
    screen.observeField("action", "onScreenAction")
    if m.stack.Count() > 0 then m.stack.Peek().visible = false
    m.screens.appendChild(screen)
    m.stack.Push(screen)
    ' Un diccionario vacío podría no avisar el cambio: siempre lleva al menos una clave.
    if params = invalid then params = {}
    params.openedAt = NowSec()
    screen.params = params
    screen.setFocus(true)
end sub

sub PopScreen()
    if m.stack.Count() <= 1 then return
    screen = m.stack.Pop()
    screen.unobserveField("action")
    m.screens.removeChild(screen)
    top = m.stack.Peek()
    top.visible = true
    top.setFocus(true)
    top.resumed = true
end sub

sub ReplaceAll(name as string, params as object)
    while m.stack.Count() > 0
        screen = m.stack.Pop()
        screen.unobserveField("action")
        m.screens.removeChild(screen)
    end while
    PushScreen(name, params)
end sub

sub onScreenAction(event as object)
    a = event.getData()
    t = a.type
    if t = "open" then
        PushScreen(a.screen, a.params)
    else if t = "back" then
        PopScreen()
    else if t = "result" then
        PopScreen()
        if a.tag = "main:exit" then
            if a.index = 0 then m.top.exitApp = true
        else if m.stack.Count() > 0 then
            m.stack.Peek().result = a
        end if
    else if t = "loginOk" then
        AfterLogin()
    else if t = "profileSelected" then
        EnterProfile(a.profileId)
    else if t = "switchProfile" then
        ReplaceAll("ProfilesScreen", { fromApp: true })
    else if t = "logout" then
        Logout("")
    else if t = "syncNow" then
        UploadNow()
    else if t = "homeVisible" then
        RefreshCloud()
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press or key <> "back" then return false
    if m.stack.Count() > 1 then
        PopScreen()
    else
        PushScreen("MessageScreen", { title: "Salir", message: "¿Deseas salir de la aplicación?", buttons: ["Salir", "Cancelar"], tag: "main:exit" })
    end if
    return true
end function

' ---------------------------------------------------------------------------------------
' Sesión
' ---------------------------------------------------------------------------------------

sub ApplyBranding()
    bg = RegRead("custom_background_tv_url")
    if bg = "" then bg = RegRead("custom_background_url")
    m.bgImage.uri = bg
    m.global.brandTick = m.global.brandTick + 1
end sub

' Cuenta con 2 o más dispositivos: "¿Quién está viendo?". Con 1 se entra con el perfil principal.
sub AfterLogin()
    ApplyBranding()
    if RegRead("profile_limit", "1").ToInt() >= 2 then
        ReplaceAll("ProfilesScreen", {})
    else
        EnterProfile("00")
    end if
end sub

sub EnterProfile(profileId as string)
    ' Lo pendiente se sube a la nube del perfil que se deja.
    if m.sessionStarted and m.global.profileId <> profileId then UploadNow()
    m.global.profileId = profileId
    RegWrite("active_profile", profileId)
    m.syncedProfile = ""
    m.global.uploadPending = false
    UD_Load(profileId)
    ReplaceAll("HomeScreen", {})
    SyncDown()
    if not m.sessionStarted then
        m.sessionStarted = true
        ShowSplash()
        m.noticeTimer.control = "start"
        m.securityTimer.control = "start"
        m.brandingTimer.control = "start"
        RunTask("security", {}, "onSecurity")
        RunTask("branding", {}, "onBranding")
    end if
end sub

sub Logout(message as string)
    m.securityTimer.control = "stop"
    m.brandingTimer.control = "stop"
    m.noticeTimer.control = "stop"
    m.uploadTimer.control = "stop"
    m.sessionStarted = false
    m.syncedProfile = ""
    m.splash.visible = false
    RegClearSession()
    m.global.profileId = "00"
    m.global.userData = {}
    m.global.uploadPending = false
    ApplyBranding()
    ReplaceAll("LoginScreen", { message: message })
end sub

' Splash del panel (config_<APP ID>.json o config.json del dueño). La imagen se descarga al
' momento (en el primer inicio de sesión no está en el equipo): los 2,3 s cuentan desde que se
' ve, con hasta 8 s de espera para la descarga.
sub ShowSplash()
    splash = RegRead("custom_splash_url")
    if splash = "" then return
    m.splash.visible = true
    m.splashTimer.control = "stop"
    if m.splashImage.uri = splash and m.splashImage.loadStatus = "ready" then
        m.splashTimer.duration = 2.3
    else
        m.splashTimer.duration = 8
        m.splashImage.uri = splash
    end if
    m.splashTimer.control = "start"
end sub

sub onSplashLoad()
    if not m.splash.visible then return
    status = m.splashImage.loadStatus
    if status = "ready" then
        m.splashTimer.control = "stop"
        m.splashTimer.duration = 2.3
        m.splashTimer.control = "start"
    else if status = "failed" then
        onSplashTimer()
    end if
end sub

sub onSplashTimer()
    m.splashTimer.control = "stop"
    m.splash.visible = false
end sub

sub onSecurityTimer()
    RunTask("security", {}, "onSecurity")
    if m.global.uploadPending then UploadNow()
end sub

sub onSecurity(event as object)
    res = TaskResult(event)
    if res = invalid or not m.sessionStarted then return
    if res.action = "logout" then
        Logout(AsStr(res.message))
    end if
end sub

sub onBrandingTimer()
    RunTask("branding", {}, "onBranding")
end sub

sub onBranding(event as object)
    res = TaskResult(event)
    if res <> invalid and res.changed = true then ApplyBranding()
end sub

' Aviso de vencimiento (Android: WebView sobre la pantalla principal tras el splash).
sub onNoticeTimer()
    RunTask("notice", {}, "onNotice")
end sub

sub onNotice(event as object)
    res = TaskResult(event)
    if res = invalid or not m.sessionStarted then return
    text = AsStr(res.text)
    if Len(text) > 5 then PushScreen("NoteScreen", { title: AsStr(res.title), text: text })
end sub

' ---------------------------------------------------------------------------------------
' Nube del perfil
' ---------------------------------------------------------------------------------------

sub SyncDown()
    m.lastPull = NowSec()
    RunTask("syncDown", { profileId: m.global.profileId }, "onSyncDown")
end sub

' Fusión de tres vías con la copia anterior de la nube como base; luego la nube leída pasa a
' ser la base. Solo se sube si lo local tiene algo que la nube no.
sub onSyncDown(event as object)
    res = TaskResult(event)
    if res = invalid or res.ok <> true or res.profileId <> m.global.profileId then return
    m.syncedProfile = res.profileId
    needsUpload = UD_ApplyCloud(res.profileId, res.base, res.doc)
    WriteAsciiFile(UD_CloudFile(res.profileId), FormatJson(res.doc))
    if needsUpload or m.global.uploadPending then UploadNow()
end sub

' Al volver al Inicio se lee la nube otra vez (como mucho una vez por minuto) para ver lo que se
' marcó en Android o iOS sin reiniciar. No mientras se sube (esa subida ya trae la nube).
sub RefreshCloud()
    profileId = m.global.profileId
    if not m.sessionStarted or m.syncedProfile <> profileId then return
    if NowSec() - m.lastPull < 60 then return
    if m.tasks <> invalid and m.tasks["syncUp" + profileId] <> invalid then return
    SyncDown()
end sub

sub onUserDataChanged()
    if not m.global.uploadPending then return
    m.uploadTimer.control = "stop"
    m.uploadTimer.control = "start"
end sub

sub onUploadTimer()
    UploadNow()
end sub

sub UploadNow()
    if not m.sessionStarted then return
    profileId = m.global.profileId
    ' Nunca se sube antes de leer la nube: en un equipo nuevo borraría datos de otros.
    if m.syncedProfile <> profileId then
        SyncDown()
        return
    end if
    m.uploadTimer.control = "stop"
    ' Una subida a la vez: si una respuesta se descartara, su base no coincidiría con lo local.
    ' La siguiente sale al terminar esta (onSyncUp).
    if m.tasks <> invalid and m.tasks["syncUp" + profileId] <> invalid then
        m.global.uploadPending = true
        return
    end if
    m.global.uploadPending = false
    m.lastPull = NowSec()
    RunTask("syncUp", { profileId: profileId, data: UD_ForUpload(UD_Data()) }, "onSyncUp", "syncUp" + profileId)
end sub

' La subida devuelve lo fusionado con la nube (lo que agregaron Android/iOS). Base = lo que se
' mandó: así se conserva lo que cambió en el Roku mientras subía.
sub onSyncUp(event as object)
    res = TaskResult(event)
    if res = invalid then return
    if res.ok <> true then
        m.global.uploadPending = true
        return
    end if
    if not m.sessionStarted then
        ' Sesión cerrada mientras subía: sin base, la próxima fusión no deduce borrados.
        DeleteFile(UD_CloudFile(res.profileId))
        return
    end if
    if res.data <> invalid then UD_ApplyCloud(res.profileId, res.request.data, res.data)
    if m.global.uploadPending then m.uploadTimer.control = "start"
end sub
