' Favoritos, historial y progreso del perfil activo (equivale a las bases Room + UserDataCache).
' El formato es el mismo documento que guarda la app Android en la nube (sync_userdata.php):
' { movies: { id: Movie }, tv_shows: { id: TvShow }, episodes: { id: Episode } }, así lo que se
' ve en Roku aparece en el celular o Android TV y al revés (mismo proveedor TMDb).

function UD_File(profileId as string) as string
    return "cachefs:/ud_" + profileId + ".json"
end function

function UD_Empty() as object
    return { movies: {}, tv_shows: {}, episodes: {} }
end function

function UD_Normalize(data as dynamic) as object
    out = UD_Empty()
    if data = invalid or GetInterface(data, "ifAssociativeArray") = invalid then return out
    for each kind in ["movies", "tv_shows", "episodes"]
        part = data[kind]
        if part <> invalid and GetInterface(part, "ifAssociativeArray") <> invalid then out[kind] = part
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
    return { year: dt.GetYear(), month: dt.GetMonth() - 1, dayOfMonth: dt.GetDayOfMonth(), hourOfDay: dt.GetHours(), minute: dt.GetMinutes(), second: dt.GetSeconds() }
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

' Fusiona lo que vino de la nube. Devuelve true si lo local tiene algo que la nube no (hay que subir).
function UD_MergeRemote(remote as dynamic) as boolean
    remote = UD_Normalize(remote)
    local = UD_Data()
    needsUpload = false
    for each kind in ["movies", "tv_shows", "episodes"]
        for each id in remote[kind]
            r = remote[kind][id]
            if GetInterface(r, "ifAssociativeArray") <> invalid then
                l = local[kind][id]
                if l = invalid then
                    local[kind][id] = r
                else
                    ru& = UD_UpdatedAt(kind, r)
                    lu& = UD_UpdatedAt(kind, l)
                    if ru& > lu& or (ru& = lu& and UD_HasData(kind, r) and not UD_HasData(kind, l)) then
                        local[kind][id] = r
                    else if lu& > ru& then
                        needsUpload = true
                    end if
                end if
            end if
        end for
        for each id in local[kind]
            if remote[kind][id] = invalid then needsUpload = true
        end for
    end for
    m.global.userData = local
    WriteAsciiFile(UD_File(m.global.profileId), FormatJson(local))
    m.global.userDataTick = m.global.userDataTick + 1
    return needsUpload
end function

' Lo que se sube: solo registros con datos del usuario.
function UD_ForUpload() as object
    data = UD_Data()
    out = {}
    for each kind in ["movies", "tv_shows", "episodes"]
        part = {}
        for each id in data[kind]
            if UD_HasData(kind, data[kind][id]) then part[id] = data[kind][id]
        end for
        if part.Count() > 0 then out[kind] = part
    end for
    return out
end function

' ---------------------------------------------------------------------------------------
' Favoritos
' ---------------------------------------------------------------------------------------

function UD_KindOf(mediaType as string) as string
    if mediaType = "tv" then return "tv_shows"
    return "movies"
end function

function UD_BaseRecord(kind as string, item as object) as object
    rec = { id: item.itemId, title: AsStr(item.title), overview: AsStr(item.overview), poster: AsStr(item.poster), banner: AsStr(item.backdrop), isFavorite: false }
    if item.rating <> invalid and AsStr(item.rating) <> "" then rec.rating = ToFloat(item.rating)
    if kind = "movies" then rec.isWatched = false
    if kind = "tv_shows" then rec.isWatching = true
    return rec
end function

function UD_IsFavorite(mediaType as string, id as string) as boolean
    rec = UD_Data()[UD_KindOf(mediaType)][id]
    return rec <> invalid and rec.isFavorite = true
end function

sub UD_SetFavorite(item as object, favorite as boolean)
    kind = UD_KindOf(item.mediaType)
    data = UD_Data()
    rec = data[kind][item.itemId]
    if rec = invalid then rec = UD_BaseRecord(kind, item)
    rec.isFavorite = favorite
    rec.favoritedAtMillis = NowMs()
    if UD_HasData(kind, rec) then
        data[kind][item.itemId] = rec
    else
        data[kind].Delete(item.itemId)
    end if
    UD_Commit(data)
end sub

' ---------------------------------------------------------------------------------------
' Progreso de reproducción
' ---------------------------------------------------------------------------------------

' Segundos donde se quedó (0 si terminó o no se empezó).
function UD_ResumeSeconds(kind as string, id as string) as integer
    rec = UD_Data()[kind][id]
    if rec = invalid or rec.watchHistory = invalid then return 0
    played& = ToLong(rec.watchHistory.lastPlaybackPositionMillis)
    return Int(played& / 1000)
end function

function UD_IsWatched(kind as string, id as string) as boolean
    rec = UD_Data()[kind][id]
    return rec <> invalid and rec.isWatched = true
end function

' playback: { mediaType: "movie"|"tv", item (película o serie), episode?, season? }
sub UD_SaveProgress(playback as object, positionSec as integer, durationSec as integer)
    if durationSec <= 0 or positionSec < 5 then return
    data = UD_Data()
    now& = NowMs()
    finished = positionSec >= durationSec * 0.9
    played& = positionSec
    dur& = durationSec
    history = { lastEngagementTimeUtcMillis: now&, lastPlaybackPositionMillis: played& * 1000, durationMillis: dur& * 1000 }
    item = playback.item

    if playback.mediaType = "movie" then
        rec = data.movies[item.itemId]
        if rec = invalid then rec = UD_BaseRecord("movies", item)
        rec.lastPlayedAtMillis = now&
        if finished then
            rec.isWatched = true
            rec.watchedDate = UD_NowCalendar()
            rec.Delete("watchHistory")
        else
            rec.watchHistory = history
        end if
        data.movies[item.itemId] = rec
    else
        ep = playback.episode
        season = playback.season
        show = data.tv_shows[item.itemId]
        if show = invalid then show = UD_BaseRecord("tv_shows", item)
        show.lastPlayedAtMillis = now&
        show.lastPlayedEpisodeId = ep.id
        show.isWatching = true
        data.tv_shows[item.itemId] = show

        rec = data.episodes[ep.id]
        if rec = invalid then
            rec = {
                id: ep.id, number: ToInt(ep.number), title: AsStr(ep.title), poster: AsStr(ep.poster), overview: AsStr(ep.overview)
                tvShow: { id: item.itemId, title: AsStr(item.title), poster: AsStr(item.poster), banner: AsStr(item.backdrop) }
                season: { id: item.itemId + "-" + AsStr(season.number), number: ToInt(season.number), title: AsStr(season.title) }
                isWatched: false
            }
        end if
        if finished then
            rec.isWatched = true
            rec.watchedDate = UD_NowCalendar()
            rec.Delete("watchHistory")
        else
            rec.watchHistory = history
        end if
        data.episodes[ep.id] = rec
    end if
    UD_Commit(data)
end sub

' Porcentaje visto (para la barra de "Continuar viendo").
function UD_Percent(rec as dynamic) as integer
    if rec = invalid or rec.watchHistory = invalid then return 0
    dur& = ToLong(rec.watchHistory.durationMillis)
    if dur& <= 0 then return 0
    played& = ToLong(rec.watchHistory.lastPlaybackPositionMillis)
    return Int(played& * 100 / dur&)
end function

' ---------------------------------------------------------------------------------------
' Listas para las filas
' ---------------------------------------------------------------------------------------

function UD_RecordToItem(kind as string, rec as object) as object
    mediaType = "movie"
    if kind = "tv_shows" then mediaType = "tv"
    return { itemId: AsStr(rec.id), mediaType: mediaType, title: AsStr(rec.title), overview: AsStr(rec.overview), poster: AsStr(rec.poster), backdrop: AsStr(rec.banner), rating: AsStr(rec.rating) }
end function

function UD_Sorted(entries as object) as object
    entries.SortBy("sortKey", "r")
    out = []
    for each e in entries
        out.Push(e.item)
    end for
    return out
end function

function UD_ContinueWatching() as object
    data = UD_Data()
    entries = []
    for each id in data.movies
        rec = data.movies[id]
        if rec.watchHistory <> invalid then
            item = UD_RecordToItem("movies", rec)
            item.progress = UD_Percent(rec)
            updated& = UD_UpdatedAt("movies", rec)
            entries.Push({ sortKey: updated&.ToStr(), item: item })
        end if
    end for
    for each id in data.tv_shows
        rec = data.tv_shows[id]
        if rec.isWatching <> false and rec.lastPlayedAtMillis <> invalid then
            item = UD_RecordToItem("tv_shows", rec)
            ep = invalid
            if rec.lastPlayedEpisodeId <> invalid then ep = data.episodes[AsStr(rec.lastPlayedEpisodeId)]
            if ep <> invalid then item.progress = UD_Percent(ep)
            updated& = UD_UpdatedAt("tv_shows", rec)
            entries.Push({ sortKey: updated&.ToStr(), item: item })
        end if
    end for
    ' Las claves son milisegundos con la misma cantidad de dígitos: el orden de texto sirve.
    return UD_Sorted(entries)
end function

function UD_Favorites() as object
    data = UD_Data()
    entries = []
    for each kind in ["movies", "tv_shows"]
        for each id in data[kind]
            rec = data[kind][id]
            if rec.isFavorite = true then
                favAt& = ToLong(rec.favoritedAtMillis)
                entries.Push({ sortKey: favAt&.ToStr(), item: UD_RecordToItem(kind, rec) })
            end if
        end for
    end for
    return UD_Sorted(entries)
end function

' "Marcar como visto / no visto" (opciones de la ficha y de cada episodio).
sub UD_SetWatched(playback as object, watched as boolean)
    data = UD_Data()
    item = playback.item
    if playback.mediaType = "movie" then
        rec = data.movies[item.itemId]
        if rec = invalid then rec = UD_BaseRecord("movies", item)
        kind = "movies"
        id = item.itemId
    else
        ep = playback.episode
        rec = data.episodes[ep.id]
        if rec = invalid then
            rec = {
                id: ep.id, number: ToInt(ep.number), title: AsStr(ep.title), poster: AsStr(ep.poster), overview: AsStr(ep.overview)
                tvShow: { id: item.itemId, title: AsStr(item.title), poster: AsStr(item.poster), banner: AsStr(item.backdrop) }
                season: { id: item.itemId + "-" + AsStr(playback.season.number), number: ToInt(playback.season.number), title: AsStr(playback.season.title) }
            }
        end if
        kind = "episodes"
        id = ep.id
    end if
    rec.isWatched = watched
    rec.Delete("watchHistory")
    if watched then rec.watchedDate = UD_NowCalendar() else rec.Delete("watchedDate")
    if UD_HasData(kind, rec) then data[kind][id] = rec else data[kind].Delete(id)
    UD_Commit(data)
end sub

' Último episodio visto de una serie: { seasonNumber, episodeId } o invalid.
function UD_LastEpisode(showId as string) as dynamic
    data = UD_Data()
    show = data.tv_shows[showId]
    if show = invalid or show.lastPlayedEpisodeId = invalid then return invalid
    ep = data.episodes[AsStr(show.lastPlayedEpisodeId)]
    if ep = invalid or ep.season = invalid then return invalid
    return { seasonNumber: ToInt(ep.season.number), episodeNumber: ToInt(ep.number), episodeId: AsStr(ep.id) }
end function
