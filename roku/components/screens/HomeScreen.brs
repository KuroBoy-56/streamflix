sub init()
    m.views = m.top.findNode("views")
    m.nav = m.top.findNode("nav")
    m.menu = m.top.findNode("menu")
    m.menu.observeField("itemSelected", "onMenuSelected")
    m.viewCache = {}
    m.current = invalid
    m.sections = []
    m.global.observeField("brandTick", "onBrand")
    InitScreen(m.menu)
end sub

sub onParams()
    ApplyBrand()
    ' Inicio, Películas y Series solo si la cuenta tiene algún proveedor que Roku sabe usar.
    hasVod = AssignedSites().Count() > 0
    ' Igual que Android: En Vivo solo si el panel le asignó canales a la cuenta (líneas, URL
    ' personal o proveedores de canales marcados).
    hasLive = RegRead("live_enabled") = "true"
    ' Si la cuenta solo tiene canales, En Vivo es el inicio.
    onlyLive = hasLive and not hasVod
    m.sections = []
    if hasVod then
        m.sections.Push({ id: "search", title: "Buscar" })
        m.sections.Push({ id: "home", title: "Inicio" })
    end if
    if hasLive then m.sections.Push({ id: "live", title: "En Vivo" })
    if hasVod then
        m.sections.Push({ id: "movies", title: "Películas" })
        m.sections.Push({ id: "tv", title: "Series" })
        m.sections.Push({ id: "favorites", title: "Favoritos" })
    end if
    m.sections.Push({ id: "settings", title: "Ajustes" })
    content = CreateObject("roSGNode", "ContentNode")
    for each s in m.sections
        item = content.createChild("ContentNode")
        item.title = s.title
    end for
    m.menu.content = content
    if hasVod then
        ShowSection("home")
    else if onlyLive then
        ShowSection("live")
    else
        ' Ningún proveedor de la cuenta funciona en Roku: solo Ajustes y el aviso.
        ShowSection("settings")
        ShowMessage("Sin proveedores para Roku", "Los proveedores de esta cuenta solo funcionan en la app de Android. Pide a tu distribuidor que marque en el panel un proveedor compatible con Roku.")
    end if
end sub

sub ApplyBrand()
    logo = RegRead("custom_logo")
    if logo = "" then logo = "pkg:/images/logo.png"
    m.top.findNode("navLogo").uri = logo
    m.top.findNode("navMini").uri = logo
    name = ""
    avatar = ""
    account = ParseJson(RegRead("profiles_cache", "null"))
    if account <> invalid and account.profiles <> invalid then
        for each p in account.profiles
            if p.id = m.global.profileId then
                name = p.name
                avatar = p.avatar
            end if
        end for
    end if
    if name = "" then name = RegRead("alpha_token")
    m.top.findNode("profileName").text = name
    m.top.findNode("avatar").uri = avatar
end sub

sub onBrand()
    ApplyBrand()
end sub

function CreateView(id as string) as object
    if id = "home" then
        view = CreateObject("roSGNode", "RowsView")
        params = { mode: "home" }
    else if id = "movies" then
        view = CreateObject("roSGNode", "RowsView")
        params = { mode: "movie" }
    else if id = "tv" then
        view = CreateObject("roSGNode", "RowsView")
        params = { mode: "tv" }
    else if id = "live" then
        view = CreateObject("roSGNode", "LiveView")
        params = {}
    else if id = "favorites" then
        view = CreateObject("roSGNode", "GridScreen")
        params = { kind: "favorites", title: "Mis favoritos", embedded: true }
    else if id = "search" then
        view = CreateObject("roSGNode", "SearchView")
        params = {}
    else
        view = CreateObject("roSGNode", "SettingsView")
        params = {}
    end if
    view.observeField("action", "onViewAction")
    m.views.appendChild(view)
    view.params = params
    return view
end function

sub ShowSection(id as string)
    view = m.viewCache[id]
    if view = invalid then
        view = CreateView(id)
        m.viewCache[id] = view
    end if
    for each key in m.viewCache
        other = m.viewCache[key]
        if not other.isSameNode(view) then
            other.visible = false
            other.active = false
        end if
    end for
    view.visible = true
    view.active = true
    m.current = view
    for i = 0 to m.sections.Count() - 1
        if m.sections[i].id = id then m.menu.jumpToItem = i
    end for
    CloseNav()
end sub

sub onMenuSelected()
    ShowSection(m.sections[m.menu.itemSelected].id)
end sub

sub OpenNav()
    m.nav.visible = true
    FocusOn(m.menu)
end sub

sub CloseNav()
    m.nav.visible = false
    if m.current <> invalid then FocusOn(m.current)
end sub

sub onViewAction(event as object)
    a = event.getData()
    if a.type = "focusMenu" then OpenNav() else m.top.action = a
end sub

sub onResult()
    if m.current <> invalid then m.current.result = m.top.result
end sub

sub onResumed()
    if m.current <> invalid then m.current.resumed = true
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "left" and not m.nav.visible then
        OpenNav()
        return true
    else if key = "right" and m.nav.visible then
        CloseNav()
        return true
    else if key = "back" then
        ' Atrás en una sección abre el menú; en el menú pregunta si salir (MainScene).
        if m.nav.visible then return false
        OpenNav()
        return true
    end if
    return false
end function
