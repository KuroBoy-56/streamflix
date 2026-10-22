sub init()
    m.grid = m.top.findNode("grid")
    m.status = m.top.findNode("status")
    m.grid.observeField("itemSelected", "onSelected")
    m.account = { limit: 1, profiles: [], avatars: [] }
    m.mode = "profiles"
    m.pending = invalid
    InitScreen(m.grid)
end sub

sub onParams()
    logo = RegRead("custom_logo")
    if logo = "" then logo = "pkg:/images/logo.png"
    m.top.findNode("logo").uri = logo
    cached = ParseJson(RegRead("profiles_cache", "null"))
    if cached <> invalid then m.account = cached
    Render()
    RunTask("profiles", { body: invalid }, "onAccount")
end sub

sub onAccount(event as object)
    res = TaskResult(event)
    if res = invalid then return
    if res.ok = true then
        m.account = res.account
        m.status.text = ""
        ' El perfil activo ya no existe: se vuelve al principal.
        exists = false
        for each p in m.account.profiles
            if p.id = m.global.profileId then exists = true
        end for
        if not exists then m.global.profileId = "00"
        Render()
    else
        m.status.text = "No se pudieron cargar los perfiles (" + AsStr(res.error) + ")."
        if m.account.profiles.Count() = 0 then
            m.account.profiles = [{ id: "00", name: "Principal", avatar: "", pin: "" }]
            Render()
        end if
    end if
end sub

sub Render()
    root = CreateObject("roSGNode", "ContentNode")
    if m.mode = "avatars" then
        m.top.findNode("title").text = "Elige un avatar"
        for each url in m.account.avatars
            node = root.createChild("ContentNode")
            node.AddFields({ itemId: url })
            node.HDPosterUrl = url
        end for
    else
        m.top.findNode("title").text = "¿Quién está viendo?"
        for each p in m.account.profiles
            node = root.createChild("ContentNode")
            node.AddFields({ itemId: p.id })
            node.title = p.name
            node.HDPosterUrl = p.avatar
            if p.avatar = "" then node.ShortDescriptionLine1 = UCase(Left(p.name, 1))
            if Len(p.pin) = 4 then node.ShortDescriptionLine2 = "Con PIN"
        end for
        if m.account.profiles.Count() < m.account.limit then
            node = root.createChild("ContentNode")
            node.AddFields({ itemId: "__add" })
            node.title = "Agregar perfil"
            node.ShortDescriptionLine1 = "+"
        end if
    end if
    count = root.getChildCount()
    columns = count
    if columns > 6 then columns = 6
    if columns < 1 then columns = 1
    m.grid.numColumns = columns
    m.grid.translation = [(1920 - (columns * 232 + (columns - 1) * 48)) / 2, 380]
    m.grid.content = root
    FocusOn(m.grid)
end sub

function ProfileById(id as string) as dynamic
    for each p in m.account.profiles
        if p.id = id then return p
    end for
    return invalid
end function

function FocusedId() as string
    node = m.grid.content.getChild(m.grid.itemFocused)
    if node = invalid then return ""
    return node.itemId
end function

sub onSelected()
    node = m.grid.content.getChild(m.grid.itemSelected)
    if node = invalid then return
    id = node.itemId
    if m.mode = "avatars" then
        m.mode = "profiles"
        Apply(m.pending, id)
        return
    end if
    if id = "__add" then
        AskInput("Nombre del nuevo perfil", "", "add")
        return
    end if
    p = ProfileById(id)
    if p = invalid then return
    if Len(p.pin) = 4 then
        AskInput("PIN de " + p.name, "", "pin:" + id, true, true)
    else
        m.top.action = { type: "profileSelected", profileId: id }
    end if
end sub

sub ShowOptions(id as string)
    p = ProfileById(id)
    if p = invalid then return
    buttons = ["Cambiar nombre"]
    if m.account.avatars.Count() > 0 then buttons.Push("Cambiar avatar")
    buttons.Push("Poner PIN")
    if Len(p.pin) = 4 then buttons.Push("Quitar PIN")
    if id <> "00" then buttons.Push("Eliminar")
    buttons.Push("Cancelar")
    ShowMessage(p.name, "¿Qué deseas cambiar de este perfil?", buttons, "opts:" + id)
end sub

sub onResult()
    r = m.top.result
    tag = AsStr(r.tag)
    kind = SubBeforeColon(tag)
    id = Mid(tag, Len(kind) + 2)
    if r.index < 0 then return
    p = ProfileById(id)
    if kind = "pin" or kind = "pinopts" then
        if p <> invalid and AsStr(r.text) = p.pin then
            if kind = "pin" then m.top.action = { type: "profileSelected", profileId: id } else ShowOptions(id)
        else
            m.status.text = "PIN incorrecto"
        end if
    else if kind = "add" then
        name = AsStr(r.text).Trim()
        if name = "" then return
        m.pending = { action: "add", name: name }
        if m.account.avatars.Count() > 0 then
            m.mode = "avatars"
            Render()
        else
            Apply(m.pending, "")
        end if
    else if kind = "opts" then
        b = AsStr(r.button)
        if b = "Cambiar nombre" then
            AskInput("Nuevo nombre", p.name, "rename:" + id)
        else if b = "Cambiar avatar" then
            m.pending = { action: "edit", id: id }
            m.mode = "avatars"
            Render()
        else if b = "Poner PIN" then
            AskInput("PIN de 4 dígitos", "", "setpin:" + id, true, true)
        else if b = "Quitar PIN" then
            Call({ action: "edit", id: id, pin: "" })
        else if b = "Eliminar" then
            ShowMessage("Eliminar perfil", "Se borrarán el historial y los favoritos de " + p.name + ".", ["Eliminar", "Cancelar"], "del:" + id)
        end if
    else if kind = "rename" then
        name = AsStr(r.text).Trim()
        if name <> "" then Call({ action: "edit", id: id, name: name })
    else if kind = "setpin" then
        pin = AsStr(r.text)
        if Len(pin) = 4 then Call({ action: "edit", id: id, pin: pin }) else m.status.text = "El PIN debe tener 4 dígitos"
    else if kind = "del" then
        if r.index = 0 then Call({ action: "delete", id: id })
    end if
end sub

function SubBeforeColon(tag as string) as string
    i = Instr(1, tag, ":")
    if i = 0 then return tag
    return Left(tag, i - 1)
end function

sub Apply(pending as dynamic, avatar as string)
    if pending = invalid then return
    body = { action: pending.action }
    if pending.action = "add" then body.name = pending.name else body.id = pending.id
    if avatar <> "" then body.avatar = avatar
    m.pending = invalid
    Call(body)
end sub

sub Call(body as object)
    m.status.text = "Guardando..."
    RunTask("profiles", { body: body }, "onAccount")
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "options" and m.mode = "profiles" then
        id = FocusedId()
        p = ProfileById(id)
        if p <> invalid then
            if Len(p.pin) = 4 then AskInput("PIN de " + p.name, "", "pinopts:" + id, true, true) else ShowOptions(id)
        end if
        return true
    end if
    if key = "back" then
        if m.mode = "avatars" then
            m.mode = "profiles"
            m.pending = invalid
            Render()
            return true
        end if
        ' Desde la app: Atrás vuelve al perfil en uso.
        if m.top.params.fromApp = true then
            m.top.action = { type: "profileSelected", profileId: m.global.profileId }
            return true
        end if
    end if
    return false
end function
