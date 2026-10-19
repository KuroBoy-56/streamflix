sub init()
    m.list = m.top.findNode("list")
    m.list.observeField("itemFocused", "onFocused")
    m.list.observeField("itemSelected", "onSelected")
    m.syncText = ""
    InitScreen(m.list)
end sub

sub onParams()
    Build()
end sub

sub onActive()
    if m.top.active then Build()
end sub

sub Build()
    m.items = [{ id: "account", title: "Cuenta y dispositivo" }]
    ' "Aviso": la nota del panel; al elegirla se lee a pantalla completa.
    if RegRead("settings_note") <> "" then
        title = RegRead("brand_name")
        if title = "" then title = "Aviso"
        m.items.Push({ id: "note", title: title })
    end if
    m.items.Push({ id: "audio", title: "Idioma del audio: " + AudioLabel() })
    if RegRead("profile_limit", "1").ToInt() >= 2 then m.items.Push({ id: "profile", title: "Cambiar de perfil" })
    m.items.Push({ id: "sync", title: "Sincronizar ahora" })
    if RegRead("live_enabled") = "true" then m.items.Push({ id: "channels", title: "Actualizar canales" })
    m.items.Push({ id: "logout", title: "Cerrar sesión" })
    content = CreateObject("roSGNode", "ContentNode")
    for each item in m.items
        node = content.createChild("ContentNode")
        node.title = item.title
    end for
    focused = m.list.itemFocused
    m.list.content = content
    if focused > 0 and focused < m.items.Count() then m.list.jumpToItem = focused
    onFocused()
end sub

sub onFocused()
    item = m.items[m.list.itemFocused]
    if item = invalid then return
    title = item.title
    text = ""
    if item.id = "account" then
        profileName = ""
        account = ParseJson(RegRead("profiles_cache", "null"))
        if account <> invalid and account.profiles <> invalid then
            for each p in account.profiles
                if p.id = m.global.profileId then profileName = p.name
            end for
        end if
        lines = []
        if RegRead("brand_name") <> "" then lines.Push("Distribuidor: " + RegRead("brand_name"))
        lines.Push("Usuario: " + RegRead("alpha_token"))
        if profileName <> "" then lines.Push("Perfil: " + profileName)
        lines.Push("MAC: " + DeviceMac())
        lines.Push("Catálogo: " + CatalogProviderName())
        lines.Push("Equipo: " + DeviceModel())
        lines.Push("Versión: " + AppVersionName() + " (Roku)")
        text = lines.Join(Chr(10))
    else if item.id = "note" then
        text = RegRead("settings_note")
    else if item.id = "audio" then
        text = "Idioma en que se reproducen películas y series." + Chr(10) + Chr(10) + "Español latino: si el título no tiene audio en español, se activan los subtítulos en español." + Chr(10) + "Inglés: audio original en inglés."
    else if item.id = "profile" then
        text = "Abre ¿Quién está viendo? para elegir otro perfil. Cada perfil tiene su propio historial y favoritos."
    else if item.id = "sync" then
        text = "Sube ahora tus favoritos e historial a la nube de tu cuenta. Se sincronizan con tus otros equipos (Android, TV, celular)."
        if m.syncText <> "" then text = text + Chr(10) + Chr(10) + m.syncText
    else if item.id = "channels" then
        text = "Vuelve a descargar las listas de canales del panel."
    else if item.id = "logout" then
        text = "Cierra la sesión en este Roku. Tus favoritos e historial quedan guardados en la nube."
    end if
    m.top.findNode("infoTitle").text = title
    m.top.findNode("info").text = text
end sub

sub onSelected()
    item = m.items[m.list.itemSelected]
    if item.id = "note" then
        OpenScreen("NoteScreen", { title: item.title, text: RegRead("settings_note") })
    else if item.id = "audio" then
        ShowMessage("Idioma del audio", "Elige el idioma de películas y series.", ["Español latino", "Inglés"], "audio")
    else if item.id = "profile" then
        SendAction({ type: "switchProfile" })
    else if item.id = "sync" then
        SendAction({ type: "syncNow" })
        m.syncText = "Sincronización enviada."
        onFocused()
    else if item.id = "channels" then
        CreateObject("roFileSystem").Delete("cachefs:/live_cache.json")
        m.global.brandTick = m.global.brandTick + 1
        m.syncText = ""
        m.top.findNode("info").text = "Listo: los canales se descargarán de nuevo al abrir En Vivo."
    else if item.id = "logout" then
        ShowMessage("Cerrar sesión", "¿Deseas cerrar la sesión en este equipo?", ["Cerrar sesión", "Cancelar"], "logout")
    end if
end sub

sub onResult()
    r = m.top.result
    if r.tag = "logout" and r.index = 0 then SendAction({ type: "logout" })
    if r.tag = "audio" and r.index >= 0 then
        if r.index = 1 then RegWrite("audio_lang", "en") else RegWrite("audio_lang", "es")
        Build()
    end if
end sub

function AudioLabel() as string
    if AudioLanguage() = "en" then return "Inglés"
    return "Español latino"
end function
