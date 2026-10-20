' Favoritos, historial y progreso del perfil activo, en el MISMO documento de la nube que usa la
' app Android (sync_userdata.php): { "<Proveedor>": { movies, tv_shows, episodes }, ... }.
' Android guarda cada título bajo su proveedor (p. ej. "Cuevana_3") con el id de ese sitio, así
' que Roku lee todos los proveedores y escribe en el registro que Android ya tiene, o en el
' proveedor por defecto del panel con el id de ese sitio. Así "Continuar viendo" y "Mi lista"
' son una sola lista en Android y Roku.

function UD_File(profileId as string) as string
    return "cachefs:/ud_" + profileId + ".json"
end function

function UD_TmdbKey() as string
    return SafeName(CatalogProviderName())
end function

function UD_EmptyPart() as object
    return { movies: {}, tv_shows: {}, episodes: {} }
end function

function UD_NormalizePart(part as dynamic) as object
    out = UD_EmptyPart()
    if part = invalid or GetInterface(part, "ifAssociativeArray") = invalid then return out
    for each kind in ["movies", "tv_shows", "episodes"]
        if part[kind] <> invalid and GetInterface(part[kind], "ifAssociativeArray") <> invalid then out[kind] = part[kind]
    end for
    return out
end function

' Documento completo: una parte por proveedor.
function UD_Normalize(data as dynamic) as object
    out = {}
    if data = invalid or GetInterface(data, "ifAssociativeArray") = invalid then return out
    for each key in data
        if Left(key, 1) <> "_" and GetInterface(data[key], "ifAssociativeArray") <> invalid then out[key] = UD_NormalizePart(data[key])
    end for
    return out
end function

sub UD_Load(profileId as string)
    text = ReadAsciiFile(UD_File(profileId))
    data = invalid
    if text <> "" then data = ParseJson(text)
    m.global.userData = UD_Normalize(data)
end sub

function UD_Data() as object
    return UD_Normalize(m.global.userData)
end function

function UD_Part(data as object, key as string) as object
    if data[key] = invalid then data[key] = UD_EmptyPart()
    return data[key]
end function

' Guarda en el equipo y avisa a MainScene para subir a la nube (con espera, como Android).
sub UD_Commit(data as object)
    m.global.userData = data
    WriteAsciiFile(UD_File(m.global.profileId), FormatJson(data))
    m.global.uploadPending = true
    m.global.userDataTick = m.global.userDataTick + 1
end sub

sub UD_DeleteAllFiles()
    fs = CreateObject("roFileSystem")
    for each name in fs.GetDirectoryListing("cachefs:/")
        if Left(name, 3) = "ud_" or Left(name, 6) = "cloud_" then fs.Delete("cachefs:/" + name)
    end for
end sub

'' ---------------------------------------------------------------------------------------
' Campos con el nombre EXACTO que lee Android (Gson distingue mayúsculas). En BrightScript
' "rec.isFavorite = x" crea la clave en minúsculas, así que se escribe siempre con JSet/JObj.
' ---------------------------------------------------------------------------------------

sub JSet(aa as object, key as string, value as dynamic)
    aa.Delete(key)
    aa[key] = value
end sub

function JObj(pairs as object) as object
    aa = {}
    for each pair in pairs
        aa[pair[0]] = pair[1]
    end for
    return aa
end function

' ---------------------------------------------------------------------------------------
' Reglas de fusión (CloudSyncManager: gana el más reciente; si empatan, el que tiene datos)
' ---------------------------------------------------------------------------------------

function UD_CalendarMs(cal as dynamic) as longinteger
    result& = 0
    if cal = invalid or GetInterface(cal, "ifAssociativeArray") = invalid then return result&
    dt = CreateObject("roDateTime")
    iso = AsStr(ToInt(cal.year)) + "-" + Pad2(ToInt(cal.month) + 1) + "-" + Pad2(ToInt(cal.dayOfMonth)) + "T" + Pad2(ToInt(cal.hourOfDay)) + ":" + Pad2(ToInt(cal.minute)) + ":" + Pad2(ToInt(cal.second)) + "Z"
    dt.FromISO8601String(iso)
    secs& = dt.AsSeconds()
    result& = secs& * 1000
    return result&
end function

' Fecha como la serializa Gson para java.util.Calendar.
function UD_NowCalendar() as object
    dt = CreateObject("roDateTime")
    return JObj([["year", dt.GetYear()], ["month", dt.GetMonth() - 1], ["dayOfMonth", dt.GetDayOfMonth()], ["hourOfDay", dt.GetHours()], ["minute", dt.GetMinutes()], ["second", dt.GetSeconds()]])
end function

function UD_MaxLong(values as object) as longinteger
    best& = 0
    for each v in values
        n& = ToLong(v)
        if n& > best& then best& = n&
    end for
    return best&
end function

function UD_UpdatedAt(kind as string, rec as object) as longinteger
    engagement = invalid
    if rec.watchHistory <> invalid then engagement = rec.watchHistory.lastEngagementTimeUtcMillis
    if kind = "movies" then return UD_MaxLong([engagement, rec.favoritedAtMillis, rec.lastPlayedAtMillis, UD_CalendarMs(rec.watchedDate)])
    if kind = "tv_shows" then return UD_MaxLong([rec.favoritedAtMillis, rec.lastPlayedAtMillis])
    return UD_MaxLong([engagement, UD_CalendarMs(rec.watchedDate)])
end function

function UD_HasData(kind as string, rec as object) as boolean
    if kind = "movies" then return rec.isFavorite = true or rec.isWatched = true or rec.watchHistory <> invalid or rec.lastPlayedAtMillis <> invalid
    if kind = "tv_shows" then return rec.isFavorite = true or rec.isWatching = false or rec.lastPlayedAtMillis <> invalid
    return rec.isWatched = true or rec.watchHistory <> invalid
end function

' Fusiona el documento de la nube (todos los proveedores). Devuelve true si lo local tiene algo
' que la nube no (hay que subir).
function UD_MergeRemote(remote as dynamic) as boolean
    remote = UD_Normalize(remote)
    local = UD_Data()
    needsUpload = false
    for each key in remote
        lpart = UD_Part(local, key)
        rpart = remote[key]
        for each kind in ["movies", "tv_shows", "episodes"]
            for each id in rpart[kind]
                r = rpart[kind][id]
                if GetInterface(r, "ifAssociativeArray") <> invalid then
                    l = lpart[kind][id]
                    if l = invalid then
                        lpart[kind][id] = r
                    else
                        ru& = UD_UpdatedAt(kind, r)
                        lu& = UD_UpdatedAt(kind, l)
                        if ru& > lu& or (ru& = lu& and UD_HasData(kind, r) and not UD_HasData(kind, l)) then
                            lpart[kind][id] = r
                        else if lu& > ru& then
                            needsUpload = true
                        end if
                    end if
                end if
            end for
        end for
    end for
    for each key in local
        for each kind in ["movies", "tv_shows", "episodes"]
            for each id in local[key][kind]
                if remote[key] = invalid or remote[key][kind][id] = invalid then needsUpload = true
            end for
        end for
    end for
    m.global.userData = local
    WriteAsciiFile(UD_File(m.global.profileId), FormatJson(local))
    m.global.userDataTick = m.global.userDataTick + 1
    return needsUpload
end function

' Lo que se sube: todos los proveedores, solo registros con datos del usuario.
function UD_ForUpload() as object
    data = UD_Data()
    out = {}
    for each key in data
        part = {}
        for each kind in ["movies", "tv_shows", "episodes"]
            records = {}
            for each id in data[key][kind]
                if UD_HasData(kind, data[key][kind][id]) then records[id] = data[key][kind][id]
            end for
            if records.Count() > 0 then part[kind] = records
        end for
        out[key] = part
    end for
    return out
end function

' ---------------------------------------------------------------------------------------
' Dónde está (o irá) el registro de un título
' ---------------------------------------------------------------------------------------

function UD_KindOf(mediaType as string) as string
    if mediaType = "tv" then return "tv_shows"
    return "movies"
end function

' item: el de la ficha (id de TMDb; extra.pkey/extra.pid si vino de un registro de la nube).
' links: ids del título en los sitios (ApiLinks), por clave de proveedor. -> { key, id, link }
function UD_Locate(data as object, kind as string, item as object, links as dynamic) as object
    if links = invalid then links = {}
    extra = item.extra
    if extra <> invalid and AsStr(extra.pkey) <> "" and AsStr(extra.pid) <> "" then return { key: extra.pkey, id: extra.pid, link: links[extra.pkey] }
    mainKey = MainCloudKey()
    keys = [mainKey]
    for each key in links
        if key <> mainKey then keys.Push(key)
    end for
    for each key in keys
        link = links[key]
        if link <> invalid and data[key] <> invalid and data[key][kind][link.id] <> invalid then return { key: key, id: link.id, link: link }
    end for
    tmdbStore = UD_TmdbKey()
    if data[tmdbStore] <> invalid and data[tmdbStore][kind][item.itemId] <> invalid then return { key: tmdbStore, id: item.itemId }
    ' Registro de otro proveedor con el mismo título (Android lo guardó con su id).
    wanted = TitleKey(AsStr(item.title))
    if wanted <> "" then
        for each key in data
            for each id in data[key][kind]
                if TitleKey(AsStr(data[key][kind][id].title)) = wanted then return { key: key, id: id, link: links[key] }
            end for
        end for
    end if
    if links[mainKey] <> invalid then return { key: mainKey, id: links[mainKey].id, link: links[mainKey] }
    return { key: tmdbStore, id: item.itemId }
end function

function UD_BaseRecord(kind as string, item as object, id as string) as object
    rec = JObj([["id", id], ["title", AsStr(item.title)], ["overview", AsStr(item.overview)], ["poster", AsStr(item.poster)], ["banner", AsStr(item.backdrop)], ["isFavorite", false]])
    if item.rating <> invalid and AsStr(item.rating) <> "" then JSet(rec, "rating", ToFloat(item.rating))
    if kind = "movies" then JSet(rec, "isWatched", false)
    if kind = "tv_shows" then JSet(rec, "isWatching", true)
    return rec
end function

function UD_Get(data as object, kind as string, loc as object) as dynamic
    if data[loc.key] = invalid then return invalid
    return data[loc.key][kind][loc.id]
end function

' Episodio de la serie en el mismo proveedor: el que Android ya guardó (serie + temporada +
' número) o un id nuevo con el formato del sitio. -> { key, id, showLoc } o invalid.
function UD_LocateEpisode(data as object, showLoc as object, season as integer, number as integer, tmdbEpisodeId as string) as dynamic
    part = data[showLoc.key]
    if part <> invalid then
        for each id in part.episodes
            ep = part.episodes[id]
            if ep.tvShow <> invalid and AsStr(ep.tvShow.id) = showLoc.id and ep.season <> invalid and ToInt(ep.season.number) = season and ToInt(ep.number) = number then return { key: showLoc.key, id: id, showLoc: showLoc }
        end for
    end if
    if showLoc.key = UD_TmdbKey() then return { key: showLoc.key, id: tmdbEpisodeId, showLoc: showLoc }
    if showLoc.link <> invalid then
        id = AsStr(showLoc.link.episodeId)
        if id = "" or showLoc.link.style <> "cine" then id = LinkEpisodeId(showLoc.link, season.ToStr(), number.ToStr())
        return { key: showLoc.key, id: id, showLoc: showLoc }
    end if
    return invalid
end function

' ---------------------------------------------------------------------------------------
' Favoritos ("Mi lista")
' ---------------------------------------------------------------------------------------

function UD_IsFavorite(item as object, links as dynamic) as boolean
    data = UD_Data()
    kind = UD_KindOf(item.mediaType)
    rec = UD_Get(data, kind, UD_Locate(data, kind, item, links))
    return rec <> invalid and rec.isFavorite = true
end function

sub UD_SetFavorite(item as object, links as dynamic, favorite as boolean)
    data = UD_Data()
    kind = UD_KindOf(item.mediaType)
    loc = UD_Locate(data, kind, item, links)
    part = UD_Part(data, loc.key)
    rec = part[kind][loc.id]
    if rec = invalid then rec = UD_BaseRecord(kind, item, loc.id)
    JSet(rec, "isFavorite", favorite)
    JSet(rec, "favoritedAtMillis", NowMs())
    if UD_HasData(kind, rec) then part[kind][loc.id] = rec else part[kind].Delete(loc.id)
    UD_Commit(data)
end sub

' ---------------------------------------------------------------------------------------
' Progreso de reproducción
' ---------------------------------------------------------------------------------------

function UD_ResumeOf(rec as dynamic) as integer
    if rec = invalid or rec.watchHistory = invalid then return 0
    played& = ToLong(rec.watchHistory.lastPlaybackPositionMillis)
    return Int(played& / 1000)
end function

' Película: segundos donde se quedó.
function UD_MovieResume(item as object, links as dynamic) as integer
    data = UD_Data()
    return UD_ResumeOf(UD_Get(data, "movies", UD_Locate(data, "movies", item, links)))
end function

function UD_MovieWatched(item as object, links as dynamic) as boolean
    data = UD_Data()
    rec = UD_Get(data, "movies", UD_Locate(data, "movies", item, links))
    return rec <> invalid and rec.isWatched = true
end function

' Registro de un episodio (serie de la ficha, temporada y número) o invalid.
function UD_EpisodeRecord(item as object, links as dynamic, season as integer, number as integer, tmdbEpisodeId as string) as dynamic
    data = UD_Data()
    loc = UD_LocateEpisode(data, UD_Locate(data, "tv_shows", item, links), season, number, tmdbEpisodeId)
    if loc = invalid then return invalid
    return UD_Get(data, "episodes", loc)
end function

function UD_EpisodeRecordFor(data as object, showLoc as object, season as integer, number as integer, tmdbEpisodeId as string, base as object) as object
    loc = UD_LocateEpisode(data, showLoc, season, number, tmdbEpisodeId)
    if loc = invalid then
        ' Proveedor de Android cuyos ids Roku no sabe armar: la serie y el episodio van a TMDb.
        showLoc = { key: UD_TmdbKey(), id: base.showTmdbId }
        loc = { key: showLoc.key, id: tmdbEpisodeId, showLoc: showLoc }
    end if
    return loc
end function

' playback: { mediaType: "movie"|"tv", item, episode?, season?, links? }
sub UD_SaveProgress(playback as object, positionSec as integer, durationSec as integer)
    if durationSec <= 0 or positionSec < 5 then return
    finished = positionSec >= durationSec * 0.9
    played& = positionSec
    dur& = durationSec
    now& = NowMs()
    history = JObj([["lastEngagementTimeUtcMillis", now&], ["lastPlaybackPositionMillis", played& * 1000], ["durationMillis", dur& * 1000]])
    UD_UpdatePlayback(playback, now&, finished, history)
end sub

' "Marcar como visto / no visto" (ficha y episodios).
sub UD_SetWatched(playback as object, watched as boolean)
    UD_UpdatePlayback(playback, invalid, watched, invalid)
end sub

' Cambia el registro de una película o episodio (y su serie). history invalid = sin progreso.
sub UD_UpdatePlayback(playback as object, playedAt as dynamic, watched as boolean, history as dynamic)
    data = UD_Data()
    item = playback.item
    links = playback.links
    if playback.mediaType = "movie" then
        loc = UD_Locate(data, "movies", item, links)
        part = UD_Part(data, loc.key)
        rec = part.movies[loc.id]
        if rec = invalid then rec = UD_BaseRecord("movies", item, loc.id)
        if playedAt <> invalid then JSet(rec, "lastPlayedAtMillis", playedAt)
        UD_ApplyWatch(rec, watched, history)
        if UD_HasData("movies", rec) then part.movies[loc.id] = rec else part.movies.Delete(loc.id)
    else
        ep = playback.episode
        season = playback.season
        showLoc = UD_Locate(data, "tv_shows", item, links)
        loc = UD_EpisodeRecordFor(data, showLoc, ToInt(season.number), ToInt(ep.number), AsStr(ep.id), { showTmdbId: item.itemId })
        showLoc = loc.showLoc
        part = UD_Part(data, loc.key)
        if playedAt <> invalid then
            show = part.tv_shows[showLoc.id]
            if show = invalid then show = UD_BaseRecord("tv_shows", item, showLoc.id)
            JSet(show, "lastPlayedAtMillis", playedAt)
            JSet(show, "lastPlayedEpisodeId", loc.id)
            JSet(show, "isWatching", true)
            part.tv_shows[showLoc.id] = show
        end if
        seasonId = item.itemId + "-" + AsStr(season.number)
        if showLoc.link <> invalid then seasonId = LinkSeasonId(showLoc.link, AsStr(season.number))
        rec = part.episodes[loc.id]
        if rec = invalid then
            rec = JObj([["id", loc.id], ["number", ToInt(ep.number)], ["title", AsStr(ep.title)], ["poster", AsStr(ep.poster)], ["overview", AsStr(ep.overview)], ["isWatched", false]])
            JSet(rec, "tvShow", JObj([["id", showLoc.id], ["title", AsStr(item.title)], ["poster", AsStr(item.poster)], ["banner", AsStr(item.backdrop)]]))
            JSet(rec, "season", JObj([["id", seasonId], ["number", ToInt(season.number)], ["title", AsStr(season.title)]]))
        end if
        UD_ApplyWatch(rec, watched, history)
        if UD_HasData("episodes", rec) then part.episodes[loc.id] = rec else part.episodes.Delete(loc.id)
    end if
    UD_Commit(data)
end sub

sub UD_ApplyWatch(rec as object, watched as boolean, history as dynamic)
    if watched then
        JSet(rec, "isWatched", true)
        JSet(rec, "watchedDate", UD_NowCalendar())
        rec.Delete("watchHistory")
    else if history <> invalid then
        JSet(rec, "watchHistory", history)
    else
        JSet(rec, "isWatched", false)
        rec.Delete("watchedDate")
        rec.Delete("watchHistory")
    end if
end sub

' Porcentaje visto (para la barra de "Continuar viendo").
function UD_Percent(rec as dynamic) as integer
    if rec = invalid or rec.watchHistory = invalid then return 0
    dur& = ToLong(rec.watchHistory.durationMillis)
    if dur& <= 0 then return 0
    played& = ToLong(rec.watchHistory.lastPlaybackPositionMillis)
    return Int(played& * 100 / dur&)
end function

' Último episodio visto de una serie: { seasonNumber, episodeNumber } o invalid.
function UD_LastEpisode(item as object, links as dynamic) as dynamic
    data = UD_Data()
    loc = UD_Locate(data, "tv_shows", item, links)
    show = UD_Get(data, "tv_shows", loc)
    if show = invalid or show.lastPlayedEpisodeId = invalid then return invalid
    ep = data[loc.key].episodes[AsStr(show.lastPlayedEpisodeId)]
    if ep = invalid or ep.season = invalid then return invalid
    return { seasonNumber: ToInt(ep.season.number), episodeNumber: ToInt(ep.number) }
end function

' ---------------------------------------------------------------------------------------
' Listas para las filas (de todos los proveedores, sin repetir títulos)
' ---------------------------------------------------------------------------------------

function UD_RecordToItem(key as string, kind as string, rec as object) as object
    mediaType = "movie"
    if kind = "tv_shows" then mediaType = "tv"
    item = { itemId: AsStr(rec.id), mediaType: mediaType, title: AsStr(rec.title), overview: AsStr(rec.overview), poster: AsStr(rec.poster), backdrop: AsStr(rec.banner), rating: AsStr(rec.rating) }
    ' Registro de otro proveedor: la ficha busca el título en TMDb y escribe en este registro.
    item.extra = { pkey: key, pid: AsStr(rec.id), match: key <> UD_TmdbKey() }
    return item
end function

function UD_Collect(entries as object) as object
    entries.SortBy("sortKey", "r")
    out = []
    seen = {}
    for each e in entries
        k = e.item.mediaType + TitleKey(e.item.title)
        if not seen.DoesExist(k) then
            seen[k] = true
            out.Push(e.item)
        end if
    end for
    return out
end function

' Canales en vivo que Android guarda en la nube ("Live", listas IPTV, Vavoo): van solo en En Vivo.
function UD_IsLiveKey(key as string) as boolean
    k = LCase(key)
    return k = "live" or Instr(1, k, "iptv") > 0 or Instr(1, k, "vavoo") > 0 or Instr(1, k, "live_tv") > 0
end function

' Claves de proveedores de películas y series (sin los de canales en vivo).
function UD_MediaKeys(data as object) as object
    keys = []
    for each key in data
        if not UD_IsLiveKey(key) then keys.Push(key)
    end for
    return keys
end function

function UD_ContinueWatching() as object
    data = UD_Data()
    entries = []
    for each key in UD_MediaKeys(data)
        part = data[key]
        for each id in part.movies
            rec = part.movies[id]
            if rec.watchHistory <> invalid then
                item = UD_RecordToItem(key, "movies", rec)
                item.progress = UD_Percent(rec)
                updated& = UD_UpdatedAt("movies", rec)
                entries.Push({ sortKey: updated&.ToStr(), item: item })
            end if
        end for
        for each id in part.tv_shows
            rec = part.tv_shows[id]
            if rec.isWatching <> false and rec.lastPlayedAtMillis <> invalid then
                item = UD_RecordToItem(key, "tv_shows", rec)
                ep = invalid
                if rec.lastPlayedEpisodeId <> invalid then ep = part.episodes[AsStr(rec.lastPlayedEpisodeId)]
                if ep <> invalid then item.progress = UD_Percent(ep)
                updated& = UD_UpdatedAt("tv_shows", rec)
                entries.Push({ sortKey: updated&.ToStr(), item: item })
            end if
        end for
    end for
    ' Las claves son milisegundos con la misma cantidad de dígitos: el orden de texto sirve.
    return UD_Collect(entries)
end function

function UD_Favorites() as object
    data = UD_Data()
    entries = []
    for each key in UD_MediaKeys(data)
        for each kind in ["movies", "tv_shows"]
            for each id in data[key][kind]
                rec = data[key][kind][id]
                if rec.isFavorite = true then
                    favAt& = ToLong(rec.favoritedAtMillis)
                    entries.Push({ sortKey: favAt&.ToStr(), item: UD_RecordToItem(key, kind, rec) })
                end if
            end for
        end for
    end for
    return UD_Collect(entries)
end function
