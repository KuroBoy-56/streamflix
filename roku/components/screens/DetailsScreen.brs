sub init()
    m.buttons = m.top.findNode("buttons")
    m.rows = m.top.findNode("rows")
    m.buttons.observeField("buttonSelected", "onButton")
    m.rows.observeField("rowItemSelected", "onRowSelected")
    m.details = invalid
    m.actions = []
    InitScreen(m.buttons)
end sub

sub onParams()
    m.item = m.top.params.item
    ShowItem(m.item)
    RenderButtons()
    RunTask("details", { mediaType: m.item.mediaType, id: m.item.itemId }, "onDetails")
end sub

sub ShowItem(item as object)
    m.top.findNode("title").text = item.title
    m.top.findNode("overview").text = item.overview
    m.top.findNode("poster").uri = item.poster
    m.top.findNode("backdrop").uri = item.backdrop
end sub

sub onDetails(event as object)
    res = TaskResult(event)
    if res = invalid then return
    if res.error <> invalid then
        m.top.findNode("message").text = res.error
        return
    end if
    m.details = res
    ' Se conserva lo que vino de la fila y se completa con la ficha.
    for each key in res.item
        if AsStr(res.item[key]) <> "" then m.item[key] = res.item[key]
    end for
    ShowItem(m.item)
    meta = []
    if m.item.year <> "" then meta.Push(m.item.year)
    if m.item.rating <> "" then meta.Push("★ " + m.item.rating)
    if res.runtime > 0 then meta.Push(res.runtime.ToStr() + " min")
    if m.item.mediaType = "tv" and res.seasons.Count() > 0 then
        n = res.seasons.Count()
        if n = 1 then meta.Push("1 temporada") else meta.Push(n.ToStr() + " temporadas")
    end if
    if res.genres <> "" then meta.Push(res.genres)
    m.top.findNode("meta").text = meta.Join("   ·   ")
    m.top.findNode("tagline").text = res.tagline

    root = CreateObject("roSGNode", "ContentNode")
    if res.cast.Count() > 0 then root.appendChild(RowNode("Reparto", res.cast))
    if res.recommendations.Count() > 0 then root.appendChild(RowNode("Recomendaciones", res.recommendations))
    m.rows.content = root
    m.rows.visible = root.getChildCount() > 0
    RenderButtons()
end sub

sub RenderButtons()
    labels = []
    m.actions = []
    id = m.item.itemId
    if m.item.mediaType = "movie" then
        resume = UD_ResumeSeconds("movies", id)
        if resume > 30 then
            labels.Push("Continuar (" + FormatClock(resume) + ")")
            m.actions.Push("resume")
            labels.Push("Desde el inicio")
            m.actions.Push("play")
        else
            labels.Push("Reproducir")
            m.actions.Push("play")
        end if
    else
        last = UD_LastEpisode(id)
        if last <> invalid then
            labels.Push("Continuar T" + last.seasonNumber.ToStr() + ":E" + last.episodeNumber.ToStr())
            m.actions.Push("continue")
        end if
        labels.Push("Ver episodios")
        m.actions.Push("episodes")
    end if
    if UD_IsFavorite(m.item.mediaType, id) then labels.Push("Quitar de favoritos") else labels.Push("Agregar a favoritos")
    m.actions.Push("favorite")
    if m.item.mediaType = "movie" then
        if UD_IsWatched("movies", id) then labels.Push("Marcar como no visto") else labels.Push("Marcar como visto")
        m.actions.Push("watched")
    end if
    SetButtons(m.buttons, labels, m.buttons.buttonFocused)
end sub

sub onResumed()
    RenderButtons()
end sub

sub onButton()
    act = m.actions[m.buttons.buttonSelected]
    if act = "play" then
        Play(0)
    else if act = "resume" then
        Play(UD_ResumeSeconds("movies", m.item.itemId))
    else if act = "episodes" then
        OpenEpisodes(invalid)
    else if act = "continue" then
        OpenEpisodes(UD_LastEpisode(m.item.itemId))
    else if act = "favorite" then
        UD_SetFavorite(m.item, not UD_IsFavorite(m.item.mediaType, m.item.itemId))
        RenderButtons()
    else if act = "watched" then
        UD_SetWatched({ mediaType: "movie", item: m.item }, not UD_IsWatched("movies", m.item.itemId))
        RenderButtons()
    end if
end sub

sub Play(startAt as integer)
    OpenScreen("PlayerScreen", { mediaType: "movie", item: m.item, startAt: startAt })
end sub

sub OpenEpisodes(last as dynamic)
    if m.details = invalid then
        m.top.findNode("message").text = "Cargando temporadas..."
        return
    end if
    if m.details.seasons.Count() = 0 then
        m.top.findNode("message").text = "Esta serie no tiene episodios disponibles."
        return
    end if
    OpenScreen("EpisodesScreen", { item: m.item, seasons: m.details.seasons, last: last })
end sub

sub onRowSelected()
    f = m.rows.rowItemSelected
    node = m.rows.content.getChild(f[0]).getChild(f[1])
    item = NodeToItem(node)
    if item.mediaType = "person" then
        OpenScreen("GridScreen", { kind: "person", id: item.itemId, title: item.title })
    else
        OpenScreen("DetailsScreen", { item: item })
    end if
end sub

sub onResult()
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "down" and m.buttons.isInFocusChain() and m.rows.visible and m.rows.content <> invalid then
        FocusOn(m.rows)
        return true
    else if key = "up" and m.rows.isInFocusChain() then
        FocusOn(m.buttons)
        return true
    end if
    return false
end function
