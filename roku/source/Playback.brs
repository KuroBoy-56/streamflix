' Contenido reproducible para el nodo Video (canales en vivo y películas/episodios).

function GuessFormat(url as string) as string
    path = LCase(url)
    q = Instr(1, path, "?")
    if q > 0 then path = Left(path, q - 1)
    if Right(path, 5) = ".m3u8" or Right(path, 4) = ".m3u" then return "hls"
    if Right(path, 4) = ".mpd" then return "dash"
    if Right(path, 4) = ".mp4" then return "mp4"
    if Right(path, 4) = ".mkv" then return "mkv"
    if Right(path, 4) = ".mp3" then return "mp3"
    ' .ts y otros: Roku detecta el formato por la respuesta del servidor.
    if Right(path, 3) = ".ts" then return ""
    return "hls"
end function

function StreamContent(url as string, headers as dynamic, title as string, live as boolean) as object
    c = CreateObject("roSGNode", "ContentNode")
    c.url = url
    c.title = title
    format = GuessFormat(url)
    if format <> "" then c.streamFormat = format
    if headers <> invalid and headers.Count() > 0 then c.HttpHeaders = headers
    c.Live = live
    return c
end function

' Fuentes de un canal en orden de intento. Las líneas Xtream (/live/u/p/123.ts) se prueban
' primero como .m3u8, que Roku reproduce mejor.
function ChannelSources(node as object) as object
    list = []
    re = CreateObject("roRegex", "/live/[^/]+/[^/]+/\d+\.ts$", "i")
    sources = node.sources
    if sources = invalid or sources.Count() = 0 then sources = [{ url: node.url, headers: [] }]
    for each s in sources
        if re.IsMatch(s.url) then list.Push({ url: Left(s.url, Len(s.url) - 3) + ".m3u8", headers: s.headers })
        list.Push(s)
    end for
    return list
end function

sub SetupVideo(video as object)
    agent = CreateObject("roHttpAgent")
    agent.SetCertificatesFile("common:/certs/ca-bundle.crt")
    agent.InitClientCertificates()
    video.setHttpAgent(agent)
end sub

' Prioridad de una pista de audio (interfaz en español): latino 3, español 2, inglés 1, otro 0.
function AudioRank(t as object) as integer
    language = LCase(AsStr(t.Language))
    name = LCase(AsStr(t.Name))
    if Instr(1, name, "latin") > 0 or Instr(1, name, "lat)") > 0 or Instr(1, name, "[lat") > 0 or Instr(1, language, "419") > 0 or Instr(1, language, "mx") > 0 then return 3
    if Left(language, 2) = "es" or Left(language, 3) = "spa" or Instr(1, name, "spanish") > 0 or Instr(1, name, "español") > 0 or Instr(1, name, "espanol") > 0 or Instr(1, name, "castellano") > 0 then return 2
    if Left(language, 2) = "en" or Instr(1, name, "english") > 0 or Instr(1, name, "inglés") > 0 or Instr(1, name, "ingles") > 0 then return 1
    return 0
end function

' Pista de audio en el idioma elegido. Español: latino > español > inglés (si falta el español
' queda el inglés, nunca italiano u otro). Inglés: inglés > latino > español. invalid = ninguna.
function PickAudio(tracks as dynamic, lang as string) as dynamic
    best = invalid
    bestScore = 0
    if tracks = invalid then return invalid
    for each t in tracks
        score = AudioRank(t)
        if lang = "en" and score = 1 then score = 4
        if score > bestScore then
            best = t
            bestScore = score
        end if
    end for
    return best
end function

' Pistas para el menú del botón *: latino, español, inglés y luego el resto (en su orden).
function SortAudioTracks(tracks as dynamic) as object
    entries = []
    if tracks = invalid then return entries
    for each t in tracks
        entries.Push({ rank: -AudioRank(t), track: t })
    end for
    entries.SortBy("rank")
    out = []
    for each e in entries
        out.Push(e.track)
    end for
    return out
end function

' Idioma del servidor en el reproductor ("Servidor 1 · Latino"), sin el nombre del sitio.
function ServerLabel(index as integer, server as object) as string
    names = { "lat": "Latino", "cast": "Castellano", "sub": "Subtitulado", "en": "Inglés", "multi": "Multiaudio" }
    label = "Servidor " + (index + 1).ToStr()
    lang = names[AsStr(server.lang)]
    if lang <> invalid then label = label + " · " + lang
    return label
end function

' Subtítulos completos en español (no "forced", que solo traducen partes sueltas).
function PickSubtitle(tracks as dynamic) as dynamic
    best = invalid
    bestScore = 0
    if tracks = invalid then return invalid
    for each t in tracks
        language = LCase(AsStr(t.Language))
        text = LCase(AsStr(t.TrackName) + " " + AsStr(t.Description))
        score = 0
        if Left(language, 2) = "es" or Left(language, 3) = "spa" or Instr(1, text, "spanish") > 0 or Instr(1, text, "español") > 0 then score = 1
        if score > 0 and (Instr(1, text, "latin") > 0 or Instr(1, language, "419") > 0) then score = 2
        if Instr(1, text, "forced") > 0 or Instr(1, text, "forzad") > 0 then score = 0
        if score > bestScore then
            best = t
            bestScore = score
        end if
    end for
    return best
end function

' Nombre en español de una pista ("spa" + "Latino" -> "Español latino", "eng" -> "Inglés").
function TrackLabel(language as dynamic, name as dynamic) as string
    code = LCase(AsStr(language))
    text = AsStr(name).Trim()
    lower = LCase(text)
    names = { es: "Español", spa: "Español", esp: "Español", en: "Inglés", eng: "Inglés", it: "Italiano", ita: "Italiano", pt: "Portugués", por: "Portugués", fr: "Francés", fra: "Francés", fre: "Francés", de: "Alemán", deu: "Alemán", ger: "Alemán", ja: "Japonés", jpn: "Japonés", ko: "Coreano", kor: "Coreano", zh: "Chino", chi: "Chino", zho: "Chino", ru: "Ruso", rus: "Ruso" }
    base = names[Left(code, 3)]
    if base = invalid then base = names[Left(code, 2)]
    if base = invalid then
        if text <> "" then return text
        if code <> "" then return UCase(code)
        return "Original"
    end if
    if base = "Español" and (Instr(1, code, "419") > 0 or Instr(1, code, "mx") > 0 or Instr(1, lower, "lat") > 0) then return "Español latino"
    if base = "Español" and (Instr(1, lower, "castellano") > 0 or Instr(1, lower, "spain") > 0 or Instr(1, lower, "españa") > 0) then return "Español (España)"
    if Instr(1, lower, "forced") > 0 or Instr(1, lower, "forzad") > 0 then return base + " (forzados)"
    return base
end function
