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
