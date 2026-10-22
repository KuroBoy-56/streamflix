sub init()
    m.seasons = m.top.findNode("seasons")
    m.episodes = m.top.findNode("episodes")
    m.message = m.top.findNode("message")
    m.spinner = m.top.findNode("spinner")
    m.spinner.poster.uri = "pkg:/images/spinner.png"
    m.spinner.poster.width = 96
    m.spinner.poster.height = 96
    m.seasons.observeField("itemFocused", "onSeasonFocused")
    m.seasons.observeField("itemSelected", "onSeasonSelected")
    m.episodes.observeField("itemSelected", "onEpisodeSelected")
    m.list = []
    m.seasonIndex = -1
    m.focusEpisode = ""
    InitScreen(m.seasons)
end sub

sub onParams()
    p = m.top.params
    m.item = p.item
    SetText(m.top.findNode("title"), m.item.title)
    m.top.findNode("backdrop").uri = m.item.backdrop
    content = CreateObject("roSGNode", "ContentNode")
    start = 0
    for i = 0 to p.seasons.Count() - 1
        s = p.seasons[i]
        node = content.createChild("ContentNode")
        title = s.title
        if title = "" then title = "Temporada " + s.number.ToStr()
        node.title = title
        if p.last <> invalid and s.number = p.last.seasonNumber then start = i
    end for
    m.seasons.content = content
    if p.last <> invalid then m.focusEpisode = p.last.episodeNumber.ToStr()
    m.seasons.jumpToItem = start
    LoadSeason(start)
    FocusOn(m.seasons)
end sub

sub onSeasonFocused()
    LoadSeason(m.seasons.itemFocused)
end sub

sub onSeasonSelected()
    if m.episodes.content <> invalid and m.episodes.content.getChildCount() > 0 then FocusOn(m.episodes)
end sub

sub LoadSeason(index as integer)
    if index = m.seasonIndex or index < 0 then return
    m.seasonIndex = index
    m.season = m.top.params.seasons[index]
    m.list = []
    m.episodes.content = CreateObject("roSGNode", "ContentNode")
    m.message.text = ""
    m.spinner.visible = true
    m.spinner.control = "start"
    RunTask("season", { id: m.item.itemId, number: m.season.number }, "onSeason")
end sub

sub onSeason(event as object)
    res = TaskResult(event)
    if res = invalid then return
    m.spinner.control = "stop"
    m.spinner.visible = false
    if res.error <> invalid then
        m.message.text = res.error
        return
    end if
    m.list = res.episodes
    Render()
    if m.list.Count() = 0 then m.message.text = "Esta temporada aún no tiene episodios."
    if m.focusEpisode <> "" then
        for i = 0 to m.list.Count() - 1
            if m.list[i].number.ToStr() = m.focusEpisode then
                m.episodes.jumpToItem = i
                FocusOn(m.episodes)
            end if
        end for
        m.focusEpisode = ""
    end if
end sub

sub Render()
    focused = m.episodes.itemFocused
    content = CreateObject("roSGNode", "ContentNode")
    for each ep in m.list
        node = content.createChild("ContentNode")
        node.AddFields({ progress: 0 })
        node.title = ep.number.ToStr() + ". " + ep.title
        node.description = ep.overview
        node.HDPosterUrl = ep.poster
        meta = []
        if ep.airDate <> "" then meta.Push(ep.airDate)
        if ep.runtime > 0 then meta.Push(ep.runtime.ToStr() + " min")
        rec = EpisodeRec(ep)
        if rec <> invalid and rec.isWatched = true then meta.Push("Visto")
        resume = UD_ResumeOf(rec)
        if resume > 0 then meta.Push("Continuar desde " + FormatClock(resume))
        node.ShortDescriptionLine1 = meta.Join("   ·   ")
        node.progress = UD_Percent(rec)
    end for
    m.episodes.content = content
    if focused > 0 and focused < m.list.Count() then m.episodes.jumpToItem = focused
end sub

sub onResumed()
    Render()
end sub

function EpisodePlayback(index as integer) as object
    return { mediaType: "tv", item: m.item, season: m.season, episode: m.list[index], episodes: m.list, index: index, links: m.top.params.links }
end function

' Registro del episodio en la nube (el de Android si ya existe).
function EpisodeRec(ep as object) as dynamic
    return UD_EpisodeRecord(m.item, m.top.params.links, ToInt(m.season.number), ToInt(ep.number), AsStr(ep.id))
end function

sub onEpisodeSelected()
    index = m.episodes.itemSelected
    if index < 0 or index >= m.list.Count() then return
    resume = UD_ResumeOf(EpisodeRec(m.list[index]))
    if resume > 30 then
        ShowMessage(m.list[index].title, "¿Continuar donde lo dejaste?", ["Continuar (" + FormatClock(resume) + ")", "Desde el inicio"], "resume:" + index.ToStr())
    else
        Play(index, 0)
    end if
end sub

sub onResult()
    r = m.top.result
    if Left(AsStr(r.tag), 7) <> "resume:" or r.index < 0 then return
    index = Mid(r.tag, 8).ToInt()
    if r.index = 0 then Play(index, UD_ResumeOf(EpisodeRec(m.list[index]))) else Play(index, 0)
end sub

sub Play(index as integer, startAt as integer)
    params = EpisodePlayback(index)
    params.startAt = startAt
    OpenScreen("PlayerScreen", params)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "right" and m.seasons.isInFocusChain() and m.list.Count() > 0 then
        FocusOn(m.episodes)
        return true
    else if key = "left" and m.episodes.isInFocusChain() then
        FocusOn(m.seasons)
        return true
    else if key = "options" and m.episodes.isInFocusChain() then
        index = m.episodes.itemFocused
        if index >= 0 and index < m.list.Count() then
            rec = EpisodeRec(m.list[index])
            UD_SetWatched(EpisodePlayback(index), not (rec <> invalid and rec.isWatched = true))
            Render()
        end if
        return true
    end if
    return false
end function
