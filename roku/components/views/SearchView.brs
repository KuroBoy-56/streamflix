sub init()
    m.keyboard = m.top.findNode("keyboard")
    m.grid = m.top.findNode("grid")
    m.message = m.top.findNode("message")
    m.debounce = m.top.findNode("debounce")
    m.keyboard.observeField("text", "onText")
    m.debounce.observeField("fire", "onSearch")
    m.grid.observeField("itemSelected", "onSelected")
    InitScreen(m.keyboard)
end sub

sub onText()
    m.debounce.control = "stop"
    m.debounce.control = "start"
end sub

sub onSearch()
    query = m.keyboard.text.Trim()
    if Len(query) < 2 then
        m.grid.content = invalid
        m.message.text = "Escribe el nombre de una película, serie o actor."
        m.message.visible = true
        return
    end if
    m.message.text = "Buscando..."
    m.message.visible = true
    RunTask("search", { query: query }, "onResults")
end sub

sub onResults(event as object)
    res = TaskResult(event)
    if res = invalid then return
    if res.error <> invalid then
        m.message.text = res.error
        return
    end if
    root = CreateObject("roSGNode", "ContentNode")
    for each item in res.items
        root.appendChild(ItemNode(item))
    end for
    m.grid.content = root
    m.message.text = "Sin resultados para " + Chr(34) + AsStr(res.query) + Chr(34) + "."
    m.message.visible = res.items.Count() = 0
end sub

sub onSelected()
    node = m.grid.content.getChild(m.grid.itemSelected)
    if node = invalid then return
    item = NodeToItem(node)
    if item.mediaType = "person" then
        OpenScreen("GridScreen", { kind: "person", id: item.itemId, title: item.title })
    else
        OpenScreen("DetailsScreen", { item: item })
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "right" and m.keyboard.isInFocusChain() and m.grid.content <> invalid and m.grid.content.getChildCount() > 0 then
        FocusOn(m.grid)
        return true
    else if key = "left" and m.grid.isInFocusChain() then
        FocusOn(m.keyboard)
        return true
    end if
    return false
end function
