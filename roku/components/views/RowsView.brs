sub init()
    m.rows = m.top.findNode("rows")
    m.spinner = m.top.findNode("spinner")
    m.spinner.poster.uri = "pkg:/images/spinner.png"
    m.spinner.poster.width = 96
    m.spinner.poster.height = 96
    m.message = m.top.findNode("message")
    m.rows.observeField("rowItemFocused", "onFocused")
    m.rows.observeField("rowItemSelected", "onSelected")
    m.loaded = false
    m.dirty = false
    m.localRows = 0
    m.pageRows = {}
    m.global.observeField("userDataTick", "onUserData")
    InitScreen(m.rows)
end sub

sub onActive()
    if not m.top.active then return
    if not m.loaded then Load()
    if m.dirty then RefreshLocalRows()
end sub

sub onResumed()
    if m.dirty then RefreshLocalRows()
end sub

sub onUserData()
    m.dirty = true
    if m.top.visible and m.top.isInFocusChain() then RefreshLocalRows()
end sub

sub Load()
    m.loaded = true
    m.message.visible = false
    m.spinner.visible = true
    m.spinner.control = "start"
    mode = m.top.params.mode
    if mode = "home" then RunTask("home", {}, "onLoaded") else RunTask("catalog", { mediaType: mode }, "onLoaded")
end sub

sub onLoaded(event as object)
    res = TaskResult(event)
    if res = invalid then return
    m.spinner.control = "stop"
    m.spinner.visible = false
    if res.error <> invalid then
        m.message.text = res.error + Chr(10) + "Pulsa OK para reintentar."
        m.message.visible = true
        m.loaded = false
        FocusSoft(m.top)
        ReportError("Inicio.Catalogo", res.error)
        return
    end if
    m.localRows = 0
    m.rows.content = res.content
    RefreshLocalRows()
    FocusSoft(m.rows)
    onFocused()
end sub

' "Continuar viendo" y "Mis favoritos" van arriba en Inicio y se actualizan al volver.
sub RefreshLocalRows()
    m.dirty = false
    content = m.rows.content
    if content = invalid or m.top.params.mode <> "home" then return
    focused = m.rows.rowItemFocused
    before = m.localRows
    for i = 1 to m.localRows
        content.removeChildIndex(0)
    end for
    m.localRows = 0
    rows = [["Continuar viendo", UD_ContinueWatching()], ["Mis favoritos", UD_Favorites()]]
    for i = rows.Count() - 1 to 0 step -1
        if rows[i][1].Count() > 0 then
            content.insertChild(RowNode(rows[i][0], rows[i][1]), 0)
            m.localRows = m.localRows + 1
        end if
    end for
    if focused <> invalid and focused.Count() = 2 and content.getChildCount() > 0 then
        row = focused[0] + m.localRows - before
        if row < 0 then row = 0
        if row >= content.getChildCount() then row = content.getChildCount() - 1
        col = focused[1]
        if col >= content.getChild(row).getChildCount() then col = 0
        m.rows.jumpToRowItem = [row, col]
    end if
end sub

function FocusedNode() as dynamic
    f = m.rows.rowItemFocused
    if m.rows.content = invalid or f = invalid or f.Count() <> 2 then return invalid
    row = m.rows.content.getChild(f[0])
    if row = invalid then return invalid
    return row.getChild(f[1])
end function

sub onFocused()
    node = FocusedNode()
    if node = invalid then return
    m.top.findNode("heroTitle").text = node.title
    meta = []
    if node.year <> "" then meta.Push(node.year)
    if node.rating <> "" then meta.Push("★ " + node.rating)
    if node.mediaType = "movie" then meta.Push("Película")
    if node.mediaType = "tv" then meta.Push("Serie")
    m.top.findNode("heroMeta").text = meta.Join("   ·   ")
    m.top.findNode("heroOverview").text = node.description
    m.top.findNode("backdrop").uri = node.backdrop

    ' Al acercarse al final de una fila se pide la página siguiente.
    f = m.rows.rowItemFocused
    row = m.rows.content.getChild(f[0])
    if row.paging <> invalid and row.paging.path <> invalid and not row.loading and f[1] >= row.getChildCount() - 6 then
        row.loading = true
        key = "page" + f[0].ToStr() + "_" + row.title
        m.pageRows[key] = row
        RunTask("page", row.paging, "onPage", key)
    end if
end sub

sub onPage(event as object)
    key = event.getRoSGNode().request.key
    res = TaskResult(event)
    row = m.pageRows[key]
    m.pageRows.Delete(key)
    if res = invalid or row = invalid then return
    for each item in res.items
        row.appendChild(ItemNode(item))
    end for
    paging = row.paging
    if res.done = true then
        row.paging = {}
    else
        paging.page = res.page + 1
        row.paging = paging
    end if
    row.loading = false
end sub

sub onSelected()
    node = FocusedNode()
    if node = invalid then return
    item = NodeToItem(node)
    if item.mediaType = "person" then
        OpenScreen("GridScreen", { kind: "person", id: item.itemId, title: item.title })
    else
        OpenScreen("DetailsScreen", { item: item })
    end if
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and key = "OK" and m.message.visible then
        Load()
        return true
    end if
    return false
end function
