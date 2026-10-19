sub init()
    m.video = m.top.findNode("video")
    m.status = m.top.findNode("status")
    m.osd = m.top.findNode("osd")
    m.spinner = m.top.findNode("spinner")
    m.spinner.poster.uri = "pkg:/images/spinner.png"
    m.spinner.poster.width = 96
    m.spinner.poster.height = 96
    SetupVideo(m.video)
    m.video.notificationInterval = 1
    m.video.observeField("state", "onState")
    m.video.observeField("position", "onPosition")
    m.video.observeField("availableAudioTracks", "onAudioTracks")
    m.video.observeField("availableSubtitleTracks", "onAudioTracks")
    m.top.findNode("osdTimer").observeField("fire", "onOsdTimer")
    m.channelMenu = m.top.findNode("channelMenu")
    m.menuList = m.top.findNode("menuList")
    m.menuList.observeField("itemSelected", "onMenuSelected")
    m.lastSaved = 0
    m.audioChosen = false
    m.closing = false
    InitScreen(m.video)
end sub

sub onParams()
    m.p = m.top.params
    if m.p.mediaType = "channel" then
        ' Sin la barra del Roku: OK, ◀ y ▲▼ los maneja la lista de canales.
        m.video.enableUI = false
        m.channelIndex = m.p.index
        PlayChannel()
    else
        ResolveAndPlay()
    end if
end sub

sub Busy(text as string)
    m.status.text = text
    m.spinner.visible = text <> ""
    if text <> "" then m.spinner.control = "start" else m.spinner.control = "stop"
end sub

' ---------------------------------------------------------------------------------------
' Películas y episodios
' ---------------------------------------------------------------------------------------

function PlaybackTitle() as string
    if m.p.mediaType = "movie" then return m.p.item.title
    return m.p.item.title + " · T" + AsStr(m.p.season.number) + ":E" + AsStr(m.p.episode.number) + " " + m.p.episode.title
end function

' Búsqueda automática (como Android): se arma la lista de servidores en el idioma de
' Ajustes y se prueba uno por uno; si un video falla se pasa al siguiente en el mismo punto.
sub ResolveAndPlay()
    m.video.control = "stop"
    m.lastSaved = 0
    m.servers = invalid
    m.serverIndex = -1
    m.resumeAt = 0
    if m.p.startAt <> invalid then m.resumeAt = m.p.startAt
    Busy("Buscando servidores...")
    RunTask("servers", BaseParams(), "onServers")
end sub

function BaseParams() as object
    params = { mediaType: m.p.mediaType, id: m.p.item.itemId }
    if m.p.mediaType = "tv" then
        params.season = m.p.season.number
        params.episode = m.p.episode.number
    end if
    return params
end function

sub onServers(event as object)
    res = TaskResult(event)
    if res = invalid or m.closing then return
    m.servers = res.servers
    if m.servers = invalid then m.servers = []
    TryServer(0)
end sub

sub TryServer(index as integer)
    m.video.control = "stop"
    if index >= m.servers.Count() then
        Busy("")
        m.status.text = "No se encontró un servidor que funcione para este título." + Chr(10) + "Pulsa Atrás para volver."
        ReportError("Reproduccion.Servidores", PlaybackTitle() + ": " + m.servers.Count().ToStr() + " servidores probados")
        return
    end if
    m.serverIndex = index
    server = m.servers[index]
    Busy("Servidor " + (index + 1).ToStr() + " de " + m.servers.Count().ToStr() + ": " + server.name)
    params = BaseParams()
    params.url = server.url
    params.referer = server.referer
    RunTask("extract", params, "onExtracted")
end sub

sub onExtracted(event as object)
    res = TaskResult(event)
    if res = invalid or m.closing then return
    if res.ok <> true then
        TryServer(m.serverIndex + 1)
        return
    end if
    server = m.servers[m.serverIndex]
    Busy("Cargando video...")
    content = StreamContent(res.url, res.headers, PlaybackTitle(), false)
    if AsStr(res.format) <> "" then content.streamFormat = res.format
    if m.resumeAt > 0 then content.PlayStart = m.resumeAt
    ' Los servidores de los sitios ya vienen en su idioma: solo VixSrc elige audio/subtítulos.
    m.audioChosen = server.lang <> "multi"
    m.video.content = content
    m.video.control = "play"
    FocusOn(m.video)
end sub

sub onPosition()
    if m.p.mediaType = "channel" then return
    seconds = Int(m.video.position)
    ' El progreso se guarda cada 15 s (y al salir), como el historial de Android.
    if Abs(seconds - m.lastSaved) >= 15 then
        m.lastSaved = seconds
        UD_SaveProgress(m.p, seconds, Int(m.video.duration))
    end if
end sub

' Audio en el idioma de Ajustes (español latino por defecto). Si el video no lo trae en
' español, se activan los subtítulos en español para no cambiar de idioma a mano.
sub onAudioTracks()
    if m.audioChosen then return
    tracks = m.video.availableAudioTracks
    if tracks = invalid or tracks.Count() = 0 then return
    lang = AudioLanguage()
    best = PickAudio(tracks, lang)
    if best <> invalid then
        if tracks.Count() > 1 then m.video.audioTrack = best.Track
        m.video.globalCaptionMode = "Off"
        m.audioChosen = true
    else if lang = "es" then
        subtitle = PickSubtitle(m.video.availableSubtitleTracks)
        if subtitle <> invalid then
            m.video.subtitleTrack = subtitle.TrackName
            m.video.globalCaptionMode = "On"
            m.audioChosen = true
        end if
    else
        m.audioChosen = true
    end if
end sub

sub SaveNow()
    if m.p.mediaType = "channel" then return
    duration = Int(m.video.duration)
    if duration > 0 then UD_SaveProgress(m.p, Int(m.video.position), duration)
end sub

sub PlayNextEpisode()
    if m.p.mediaType <> "tv" or m.p.episodes = invalid then
        Close()
        return
    end if
    nextIndex = m.p.index + 1
    if nextIndex >= m.p.episodes.Count() then
        Close()
        return
    end if
    p = m.p
    p.index = nextIndex
    p.episode = p.episodes[nextIndex]
    p.startAt = 0
    m.p = p
    ResolveAndPlay()
end sub

' ---------------------------------------------------------------------------------------
' Canales en vivo
' ---------------------------------------------------------------------------------------

sub PlayChannel()
    node = m.p.folder.getChild(m.channelIndex)
    if node = invalid then return
    m.sources = ChannelSources(node)
    m.sourceIndex = 0
    m.top.findNode("osdLogo").uri = node.HDPosterUrl
    m.top.findNode("osdNumber").text = "Canal " + (m.channelIndex + 1).ToStr() + " de " + m.p.folder.getChildCount().ToStr()
    m.top.findNode("osdTitle").text = node.title
    ShowOsd()
    PlaySource()
end sub

sub PlaySource()
    node = m.p.folder.getChild(m.channelIndex)
    s = m.sources[m.sourceIndex]
    Busy("Conectando...")
    m.video.control = "stop"
    m.video.content = StreamContent(s.url, s.headers, node.title, true)
    m.video.control = "play"
    FocusOn(m.video)
end sub

sub ShowOsd()
    m.osd.visible = true
    timer = m.top.findNode("osdTimer")
    timer.control = "stop"
    timer.control = "start"
end sub

sub onOsdTimer()
    m.osd.visible = false
end sub

'  Lista de canales de la categoría sin salir de la pantalla completa (LiveChannelsOverlay).
sub OpenChannelMenu()
    m.top.findNode("menuTitle").text = m.p.folder.title
    if m.menuList.content = invalid then m.menuList.content = m.p.folder
    m.menuList.jumpToItem = m.channelIndex
    m.osd.visible = false
    m.channelMenu.visible = true
    FocusOn(m.menuList)
end sub

sub CloseChannelMenu()
    m.channelMenu.visible = false
    FocusOn(m.video)
end sub

sub onMenuSelected()
    index = m.menuList.itemSelected
    CloseChannelMenu()
    if index <> m.channelIndex then
        m.channelIndex = index
        PlayChannel()
    end if
end sub

sub ZapChannel(delta as integer)
    count = m.p.folder.getChildCount()
    if count = 0 then return
    m.channelIndex = (m.channelIndex + delta + count) mod count
    PlayChannel()
end sub

' ---------------------------------------------------------------------------------------
' Estado del video
' ---------------------------------------------------------------------------------------

sub onState()
    state = m.video.state
    if state = "playing" then
        Busy("")
    else if state = "buffering" then
        m.spinner.visible = true
        m.spinner.control = "start"
    else if state = "error" then
        if m.p.mediaType <> "channel" and m.servers <> invalid then
            ' El servidor no reprodujo: el siguiente, desde donde iba.
            if m.video.position > 5 then m.resumeAt = Int(m.video.position)
            TryServer(m.serverIndex + 1)
            return
        end if
        if m.p.mediaType = "channel" and m.sourceIndex + 1 < m.sources.Count() then
            ' Misma señal en otra línea.
            m.sourceIndex = m.sourceIndex + 1
            PlaySource()
            return
        end if
        Busy("")
        m.status.text = "No se pudo reproducir (" + AsStr(m.video.errorMsg) + ")." + Chr(10) + "Pulsa Atrás para volver."
        title = ""
        if m.p.mediaType = "channel" then title = m.p.folder.getChild(m.channelIndex).title else title = PlaybackTitle()
        ReportError("Reproduccion", title + ": " + AsStr(m.video.errorMsg) + " (" + AsStr(m.video.errorCode) + ")")
    else if state = "finished" then
        if m.p.mediaType <> "channel" then
            duration = Int(m.video.duration)
            if duration > 0 then UD_SaveProgress(m.p, duration, duration)
            PlayNextEpisode()
        end if
    end if
end sub

sub Close()
    if m.closing then return
    m.closing = true
    m.video.control = "stop"
    m.top.action = { type: "back" }
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "back" and m.channelMenu.visible then
        CloseChannelMenu()
        return true
    end if
    if m.channelMenu.visible then return false
    if key = "back" then
        SaveNow()
        Close()
        return true
    end if
    if m.p.mediaType = "channel" then
        if key = "up" or key = "channelUp" then
            ZapChannel(-1)
            return true
        else if key = "down" or key = "channelDown" then
            ZapChannel(1)
            return true
        else if key = "OK" or key = "left" then
            OpenChannelMenu()
            return true
        else if key = "info" then
            ShowOsd()
            return true
        end if
    end if
    return false
end function
