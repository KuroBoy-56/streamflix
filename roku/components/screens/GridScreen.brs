sub init()
    m.grid = m.top.findNode("grid")
    m.message = m.top.findNode("message")
    m.spinner = m.top.findNode("spinner")
    m.spinner.poster.uri = "pkg:/images/spinner.png"
    m.spinner.poster.width = 96
    m.spinner.poster.height = 96
    m.grid.observeField("itemSelected", "onSelected")
    InitScreen(m.grid)
end sub

sub onParams()
    p = m.top.params
    m.top.findNode("title").text = AsStr(p.title)
    if p.embedded <> true then m.top.findNode("bg").visible = true
    if p.kind = "person" then
        m.top.findNode("title").translation = [310, 60]
        m.grid.translation = [130, 330]
        m.grid.numRows = 2
        m.spinner.visible = true
        m.spinner.control = "start"
        RunTask("person", { id: p.id }, "onPerson")
    else
        m.top.findNode("photo").visible = false
        ShowItems(UD_Favorites(), "Aún no tienes favoritos. Agrégalos desde la ficha de una película o serie.")
    end if
end sub

sub onResumed()
    if m.top.params <> invalid and m.top.params.kind = "favorites" then ShowItems(UD_Favorites(), "Aún no tienes favoritos. Agrégalos desde la ficha de una película o serie.")
end sub

sub onPerson(event as object)
    res = TaskResult(event)
    if res = invalid then return
    m.spinner.control = "stop"
    m.spinner.visible = false
    if res.error <> invalid then
        ShowItems([], res.error)
        return
    end if
    m.top.findNode("photo").uri = res.poster
    m.top.findNode("overview").text = res.overview
    ShowItems(res.items, "Sin títulos.")
end sub

sub ShowItems(items as object, emptyText as string)
    root = CreateObject("roSGNode", "ContentNode")
    for each item in items
        root.appendChild(ItemNode(item))
    end for
    focused = m.grid.itemFocused
    m.grid.content = root
    if focused > 0 and focused < items.Count() then m.grid.jumpToItem = focused
    m.message.text = emptyText
    m.message.visible = items.Count() = 0
    FocusSoft(m.grid)
end sub

sub onSelected()
    node = m.grid.content.getChild(m.grid.itemSelected)
    if node <> invalid then OpenScreen("DetailsScreen", { item: NodeToItem(node) })
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and key = "back" and m.top.params.embedded <> true then
        m.top.action = { type: "back" }
        return true
    end if
    return false
end function
