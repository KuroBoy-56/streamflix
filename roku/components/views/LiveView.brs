sub init()
    m.folders = m.top.findNode("folders")
    m.channels = m.top.findNode("channels")
    m.preview = m.top.findNode("preview")
    m.message = m.top.findNode("message")
    m.spinner = m.top.findNode("spinner")
    m.spinner.poster.uri = "pkg:/images/spinner.png"
    m.spinner.poster.width = 96
    m.spinner.poster.height = 96
    SetupVideo(m.preview)
    m.preview.observeField("state", "onPreviewState")
    m.folders.observeField("itemFocused", "onFolderFocused")
    m.folders.observeField("itemSelected", "onFolderSelected")
    m.modes = m.top.findNode("modes")
    m.modes.observeField("itemSelected", "onModeSelected")
    m.mode = "category"
    m.folderMap = []
    m.channels.observeField("itemFocused", "onChannelFocused")
    m.channels.observeField("itemSelected", "onChannelSelected")
    m.global.observeField("brandTick", "onBrand")
    m.content = invalid
    m.loadedAt = 0
    m.linesKey = ""
    m.epg = {}
    m.previewIndex = -1
    m.previewSources = []
    m.previewSource = 0
    InitScreen(m.folders)
end sub

function LinesKey() as string
    return RegRead("panel_channel_lines") + "|" + RegRead("personal_provider_url") + "|" + RegRead("panel_default_channel_line")
end function

sub onActive()
    if m.top.active then
        ' La lista se actualiza cada 30 min o si el panel cambió las líneas.
        if m.content = invalid or NowSec() - m.loadedAt > 1800 or m.linesKey <> LinesKey() then Load(false)
    else
        StopPreview()
    end if
end sub

sub onBrand()
    if m.content <> invalid and m.linesKey <> LinesKey() and m.top.active then Load(false)
end sub

sub Load(force as boolean)
    m.linesKey = LinesKey()
    if m.content = invalid then
        m.spinner.visible = true
        m.spinner.control = "start"
        m.message.text = "Cargando canales..."
    end if
    RunTask("live", { force: force }, "onLoaded")
end sub

' Copia de más de 30 min: se muestra al instante y se descarga la nueva aparte (como Android).
sub onRefreshed(event as object)
    res = TaskResult(event)
    if res = invalid or res.error <> invalid or not m.top.active then return
    folder = m.folders.itemFocused
    channel = m.channels.itemFocused
    inChannels = m.channels.isInFocusChain()
    ApplyContent(res)
    if folder < m.folderMap.Count() then
        m.folders.jumpToItem = folder
        onFolderFocused()
        if channel < m.channels.content.getChildCount() then m.channels.jumpToItem = channel
    end if
    if inChannels then FocusOn(m.channels)
end sub

sub onLoaded(event as object)
    res = TaskResult(event)
    if res = invalid then return
    m.spinner.control = "stop"
    m.spinner.visible = false
    if res.error <> invalid then
        if m.content = invalid then m.message.text = res.error
        return
    end if
    m.message.text = ""
    StopPreview()
    ApplyContent(res)
    FocusSoft(m.folders)
    if res.stale = true then RunTask("live", { force: true }, "onRefreshed", "liveRefresh")
end sub

sub ApplyContent(res as object)
    m.loadedAt = NowSec()
    m.content = res.content
    m.top.findNode("heading").text = "En Vivo  ·  " + res.total.ToStr() + " canales"
    ' Como Android: "Países" solo si la lista trae canales de 2 o más países.
    countries = 0
    for i = 0 to m.content.getChildCount() - 1
        if m.content.getChild(i).folderType = "country" then countries = countries + 1
    end for
    hasCountries = countries >= 2
    m.modes.visible = hasCountries
    if hasCountries then
        m.folders.translation = [130, 220]
        m.folders.numRows = 12
    else
        m.mode = "category"
        m.folders.translation = [130, 150]
        m.folders.numRows = 13
    end if
    RenderFolders()
    if res.epgSources <> invalid and res.epgSources.Count() > 0 then RunTask("epg", { sources: res.epgSources, ids: res.epgIds }, "onEpg")
end sub

' Carpetas del modo elegido: "Todos" y categorías, o países.
sub RenderFolders()
    ' El modo activo queda marcado en blanco translúcido.
    active = 0
    if m.mode = "country" then active = 1
    if m.modes.visible then SetPills(m.modes, ["Categorías", "Países"], "", active)
    labels = CreateObject("roSGNode", "ContentNode")
    m.folderMap = []
    for i = 0 to m.content.getChildCount() - 1
        folder = m.content.getChild(i)
        isCountry = folder.folderType = "country"
        if isCountry = (m.mode = "country") then
            item = labels.createChild("ContentNode")
            item.title = folder.title + "  (" + folder.channelCount.ToStr() + ")"
            m.folderMap.Push(i)
        end if
    end for
    m.folders.content = labels
    m.folders.jumpToItem = 0
    m.folderIndex = -1
    onFolderFocused()
end sub

sub onModeSelected()
    if m.modes.itemSelected = 1 then m.mode = "country" else m.mode = "category"
    RenderFolders()
    FocusOn(m.folders)
end sub

sub onEpg(event as object)
    res = TaskResult(event)
    if res <> invalid and res.programmes <> invalid then
        m.epg = res.programmes
        onChannelFocused()
    end if
end sub

function CurrentFolder() as dynamic
    if m.content = invalid then return invalid
    index = m.folders.itemFocused
    if index < 0 or index >= m.folderMap.Count() then return invalid
    return m.content.getChild(m.folderMap[index])
end function

sub onFolderFocused()
    folder = CurrentFolder()
    if folder = invalid or m.folders.itemFocused = m.folderIndex then return
    m.folderIndex = m.folders.itemFocused
    m.channels.content = folder
    m.channels.jumpToItem = 0
    onChannelFocused()
end sub

sub onFolderSelected()
    if m.channels.content <> invalid and m.channels.content.getChildCount() > 0 then FocusOn(m.channels)
end sub

function FocusedChannel() as dynamic
    if m.channels.content = invalid then return invalid
    return m.channels.content.getChild(m.channels.itemFocused)
end function

sub onChannelFocused()
    node = FocusedChannel()
    if node = invalid then return
    m.top.findNode("channelName").text = node.title
    meta = []
    if node.group <> "" then meta.Push(node.group)
    if node.country <> "" and node.country <> node.group then meta.Push(node.country)
    if node.sources.Count() > 1 then meta.Push(node.sources.Count().ToStr() + " señales")
    m.top.findNode("channelMeta").text = meta.Join("   ·   ")
    ShowEpg(node)
end sub

' Guía: programa actual (con barra de avance) y el siguiente.
sub ShowEpg(node as object)
    nowTitle = ""
    nowDesc = ""
    nextTitle = ""
    progress = -1
    list = invalid
    if node.epgId <> "" then list = m.epg[node.epgId]
    if list <> invalid then
        now = NowSec()
        for i = 0 to list.Count() - 1
            p = list[i]
            if p.start <= now and p.stop > now then
                nowTitle = ClockOf(p.start) + " - " + ClockOf(p.stop) + "   " + p.title
                nowDesc = p.desc
                progress = (now - p.start) * 100 / (p.stop - p.start)
                if i + 1 < list.Count() then nextTitle = "Después: " + ClockOf(list[i + 1].start) + "   " + list[i + 1].title
            end if
        end for
    end if
    m.top.findNode("nowTitle").text = nowTitle
    m.top.findNode("nowDesc").text = nowDesc
    m.top.findNode("nextTitle").text = nextTitle
    m.top.findNode("nowBarBg").visible = progress >= 0
    bar = m.top.findNode("nowBar")
    bar.visible = progress >= 0
    if progress >= 0 then bar.width = 640 * progress / 100
end sub

function ClockOf(seconds as integer) as string
    dt = CreateObject("roDateTime")
    dt.FromSeconds(seconds)
    dt.ToLocalTime()
    return Pad2(dt.GetHours()) + ":" + Pad2(dt.GetMinutes())
end function

sub onChannelSelected()
    index = m.channels.itemSelected
    if index = m.previewIndex and m.preview.state = "playing" then
        OpenFullscreen(index)
    else
        StartPreview(index)
    end if
end sub

sub StartPreview(index as integer)
    node = m.channels.content.getChild(index)
    if node = invalid then return
    m.previewIndex = index
    m.previewSources = ChannelSources(node)
    m.previewSource = 0
    PlayPreviewSource()
end sub

sub PlayPreviewSource()
    node = m.channels.content.getChild(m.previewIndex)
    s = m.previewSources[m.previewSource]
    m.top.findNode("previewHint").visible = false
    m.preview.control = "stop"
    m.preview.content = StreamContent(s.url, s.headers, node.title, true)
    m.preview.control = "play"
end sub

' Si una señal falla se pasa sola a la misma señal en otra línea.
sub onPreviewState()
    if m.preview.state = "error" then
        if m.previewSource + 1 < m.previewSources.Count() then
            m.previewSource = m.previewSource + 1
            PlayPreviewSource()
        else
            m.top.findNode("previewHint").text = "No se pudo reproducir este canal."
            m.top.findNode("previewHint").visible = true
            ReportError("Live.Reproduccion", m.channels.content.getChild(m.previewIndex).title + ": " + m.preview.errorMsg)
        end if
    end if
end sub

sub StopPreview()
    m.preview.control = "stop"
    m.previewIndex = -1
    hint = m.top.findNode("previewHint")
    hint.text = "OK: vista previa aquí" + Chr(10) + "OK otra vez o ▶: pantalla completa"
    hint.visible = true
end sub

sub OpenFullscreen(index as integer)
    StopPreview()
    OpenScreen("PlayerScreen", { mediaType: "channel", folder: m.channels.content, index: index })
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "up" and m.folders.isInFocusChain() and m.modes.visible then
        FocusOn(m.modes)
        return true
    else if key = "down" and m.modes.isInFocusChain() then
        FocusOn(m.folders)
        return true
    else if key = "right" and m.folders.isInFocusChain() and m.channels.content <> invalid and m.channels.content.getChildCount() > 0 then
        FocusOn(m.channels)
        return true
    else if (key = "left" or key = "back") and m.channels.isInFocusChain() then
        FocusOn(m.folders)
        return true
    else if key = "play" and m.channels.isInFocusChain() then
        OpenFullscreen(m.channels.itemFocused)
        return true
    else if key = "options" then
        Load(true)
        return true
    end if
    return false
end function
