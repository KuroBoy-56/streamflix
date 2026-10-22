' Obtiene el video (m3u8/mp4) de la página de un servidor (Extractor.extract en Android).
' Genérico para los que guardan el enlace en JavaScript empaquetado (StreamWish, VidHide,
' Lulu, Dropload, Uqload, MixDrop, Goodstream...) y especiales para Voe, Dood, Streamtape y Ok.ru.
' Se ejecuta SOLO dentro de ApiTask.

function ApiExtract(p as object) as object
    url = AsStr(p.url)
    if url = "vixsrc" then return ApiResolve(p)
    referer = AsStr(p.referer)

    ' Puente de Cuevana/Poseidon (player.php?h=...): la página dice a qué servidor ir.
    if Instr(1, url, "/player.php?h=") > 0 or Instr(1, url, "mysync.mov/stream/") > 0 then
        page = Http(url, { headers: { Referer: referer } })
        q = "['" + Chr(34) + "]"
        target = FirstGroup(CreateObject("roRegex", "(?:var\s+url\s*=\s*" + q + "|location\.href\s*=\s*" + q + "|location\.replace\(" + q + ")(https?://[^'" + Chr(34) + "]+)", ""), page.body)
        if target = "" then target = FirstGroup(CreateObject("roRegex", "<iframe[^>]+src=" + q + "(https?://[^'" + Chr(34) + "]+)", "i"), page.body)
        if target = "" then return { ok: false, error: "Servidor no disponible." }
        referer = OriginOf(url) + "/"
        url = target
    end if

    host = HostOf(url)
    if Instr(1, host, "voe") > 0 then return ExtractVoe(url)
    if Instr(1, host, "dood") > 0 or ArrayHas(["playmogo.com", "dsvplay.com", "myvidplay.com", "d000d.com", "do7go.com", "vide0.net", "mikaylaarealike.com"], host) then return ExtractDood(url)
    if Instr(1, host, "streamtape") > 0 or Instr(1, host, "streamta.") > 0 then return ExtractStreamtape(url)
    if host = "ok.ru" or host = "m.ok.ru" then return ExtractOkru(url)
    if Instr(1, host, "mixdrop") > 0 or Instr(1, host, "mxdrop") > 0 then url = CreateObject("roRegex", "/f/", "").ReplaceAll(url, "/e/")
    return ExtractGeneric(url, referer)
end function

function VideoResult(url as string, pageUrl as string) as object
    if Left(url, 2) = "//" then url = "https:" + url
    if Left(url, 1) = "/" then url = OriginOf(pageUrl) + url
    url = url.Replace("\/", "/")
    return { ok: true, url: url, format: GuessFormat(url), headers: ["Referer: " + OriginOf(pageUrl) + "/", "Origin: " + OriginOf(pageUrl), "User-Agent: " + BrowserUA()] }
end function

function ExtractGeneric(url as string, referer as string) as object
    headers = {}
    if referer <> "" then headers.Referer = referer
    page = Http(url, { headers: headers })
    if page.code <> 200 then return { ok: false, error: "El servidor no respondió (HTTP " + page.code.ToStr() + ")." }
    text = page.body
    scanAt = Instr(1, text, "eval(function(p,a,c,k,e,d)")
    while scanAt > 0
        finish = Instr(scanAt, text, "</script>")
        if finish = 0 then finish = Len(text)
        text = text + Chr(10) + UnpackJs(Mid(text, scanAt, finish - scanAt))
        scanAt = Instr(finish, text, "eval(function(p,a,c,k,e,d)")
    end while

    q = "[" + Chr(34) + "']"
    patterns = [
        q + "hls2" + q + "\s*:\s*" + q + "([^" + Chr(34) + "']+)"
        q + "hls4" + q + "\s*:\s*" + q + "([^" + Chr(34) + "']+)"
        "(?:file|source|src)" + q + "?\s*[:=]\s*" + q + "((?:https?:)?//[^" + Chr(34) + "']+\.(?:m3u8|mp4)[^" + Chr(34) + "']*)"
        "wurl\s*=\s*" + q + "([^" + Chr(34) + "']+)"
        "(https?://[^" + Chr(34) + "'\s]+\.m3u8[^" + Chr(34) + "'\s]*)"
        "(?:file|src)" + q + "?\s*[:=]\s*" + q + "(/[^" + Chr(34) + "']+\.m3u8[^" + Chr(34) + "']*)"
    ]
    for each pattern in patterns
        found = FirstGroup(CreateObject("roRegex", pattern, "i"), text)
        if found <> "" then return VideoResult(found, url)
    end for
    return { ok: false, error: "No se encontró el video en el servidor." }
end function

' ---------------------------------------------------------------------------------------
' Desempaquetador P.A.C.K.E.R. (JsUnpacker de Android)
' ---------------------------------------------------------------------------------------

function UnpackJs(js as string) as string
    parts = CreateObject("roRegex", "\}\s*\('(.*)',\s*(.*?),\s*(\d+),\s*'(.*?)'\.split\('\|'\)", "s").Match(js)
    if parts.Count() < 5 then return ""
    payload = parts[1].Replace("\'", "'")
    radix = parts[2].Trim().ToInt()
    if radix <= 0 then radix = 36
    symbols = parts[4].Split("|")

    src = CreateObject("roByteArray")
    src.FromAsciiString(payload)
    out = CreateObject("roByteArray")
    word = CreateObject("roByteArray")
    n = src.Count()
    for i = 0 to n
        b = -1
        if i < n then b = src[i]
        isWord = (b >= 48 and b <= 57) or (b >= 65 and b <= 90) or (b >= 97 and b <= 122) or b = 95
        if isWord then
            word.Push(b)
        else
            if word.Count() > 0 then
                index = UnbaseWord(word, radix)
                value = ""
                if index >= 0 and index < symbols.Count() then value = symbols[index]
                if value <> "" then
                    piece = CreateObject("roByteArray")
                    piece.FromAsciiString(value)
                    for k = 0 to piece.Count() - 1
                        out.Push(piece[k])
                    end for
                else
                    for k = 0 to word.Count() - 1
                        out.Push(word[k])
                    end for
                end if
                word = CreateObject("roByteArray")
            end if
            if b >= 0 then out.Push(b)
        end if
    end for
    return out.ToAsciiString()
end function

' Número de la palabra en la base del empaquetado (-1 si no es válida).
function UnbaseWord(word as object, radix as integer) as integer
    value = 0
    for k = 0 to word.Count() - 1
        b = word[k]
        if b >= 48 and b <= 57 then
            d = b - 48
        else if b >= 97 and b <= 122 then
            d = b - 87
        else if b >= 65 and b <= 90 then
            if radix > 36 then d = b - 29 else d = b - 55
        else
            return -1
        end if
        if d >= radix then return -1
        value = value * radix + d
        if value > 1000000 then return -1
    end for
    return value
end function

' ---------------------------------------------------------------------------------------
' Servidores especiales
' ---------------------------------------------------------------------------------------

' Voe: la página redirige a un espejo; el video va cifrado (rot13, base64, desplazamiento).
function ExtractVoe(url as string) as object
    page = Http(url, { headers: { Referer: url } })
    q = Chr(34)
    reJson = CreateObject("roRegex", "<script\s+type=" + q + "application/json" + q + ">(.*?)</script>", "s")
    encoded = FirstGroup(reJson, page.body)
    pageUrl = url
    if encoded = "" then
        mirror = FirstGroup(CreateObject("roRegex", "https://([a-zA-Z0-9.-]+)/", ""), page.body)
        if mirror = "" then return { ok: false, error: "Voe no respondió." }
        path = Mid(url, Instr(Instr(1, url, "://") + 3, url, "/"))
        pageUrl = "https://" + mirror + path
        page = Http(pageUrl, { headers: { Referer: url } })
        encoded = FirstGroup(reJson, page.body)
    end if
    data = VoeDecrypt(encoded)
    if data = invalid then return { ok: false, error: "No se pudo leer el video de Voe." }
    source = AsStr(data.source)
    if source = "" then source = AsStr(data.direct_access_url)
    if source = "" then return { ok: false, error: "No se pudo leer el video de Voe." }
    return VideoResult(source, pageUrl)
end function

function VoeDecrypt(encoded as string) as dynamic
    s = encoded.Trim()
    if Left(s, 2) = "[" + Chr(34) then s = Mid(s, 3, Len(s) - 4)
    ba = CreateObject("roByteArray")
    ba.FromAsciiString(s)
    for i = 0 to ba.Count() - 1
        c = ba[i]
        if c >= 65 and c <= 90 then ba[i] = ((c - 65 + 13) mod 26) + 65
        if c >= 97 and c <= 122 then ba[i] = ((c - 97 + 13) mod 26) + 97
    end for
    s = ba.ToAsciiString()
    for each pattern in ["@$", "^^", "~@", "%?", "*~", "!!", "#&"]
        s = s.Replace(pattern, "_")
    end for
    s = s.Replace("_", "")
    step1 = CreateObject("roByteArray")
    step1.FromBase64String(s)
    shifted = CreateObject("roByteArray")
    for i = step1.Count() - 1 to 0 step -1
        shifted.Push(step1[i] - 3)
    end for
    step2 = CreateObject("roByteArray")
    step2.FromBase64String(shifted.ToAsciiString())
    return ParseJson(step2.ToAsciiString())
end function

' DoodStream: /pass_md5/ da el inicio del enlace; se completa con 10 letras y el token.
function ExtractDood(url as string) as object
    embed = url.Replace("/d/", "/e/")
    page = Http(embed, { headers: { Referer: url } })
    md5Path = FirstGroup(CreateObject("roRegex", "(/pass_md5/[^'" + Chr(34) + "]*)", ""), page.body)
    if md5Path = "" then return { ok: false, error: "DoodStream no respondió." }
    base = OriginOf(embed)
    prefix = Http(base + md5Path, { headers: { Referer: embed } }).body.Trim()
    if Left(prefix, 4) <> "http" then return { ok: false, error: "DoodStream no respondió." }
    letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    random = ""
    for i = 1 to 10
        random = random + Mid(letters, Rnd(62), 1)
    end for
    parts = md5Path.Split("/")
    final = prefix + random + "?token=" + parts[parts.Count() - 1]
    return { ok: true, url: final, format: "mp4", headers: ["Referer: " + base + "/", "User-Agent: " + BrowserUA()] }
end function

function ExtractStreamtape(url as string) as object
    page = Http(url, {})
    botlink = CreateObject("roRegex", "getElementById\('botlink'\)\.innerHTML\s*=\s*'([^']+)'\s*\+\s*\('([^']+)'\)\.substring\((\d+)\)", "").Match(page.body)
    if botlink.Count() < 4 then return { ok: false, error: "Streamtape no respondió." }
    params = Mid(botlink[2], botlink[3].ToInt() + 1)
    reId = CreateObject("roRegex", "id=([^&]+)", "")
    reExp = CreateObject("roRegex", "expires=([^&]+)", "")
    reIp = CreateObject("roRegex", "ip=([^&]+)", "")
    reToken = CreateObject("roRegex", "token=([^&]+)", "")
    final = "https://streamtape.com/get_video?id=" + FirstGroup(reId, params) + "&expires=" + FirstGroup(reExp, params) + "&ip=" + FirstGroup(reIp, params) + "&token=" + FirstGroup(reToken, params) + "&stream=1"
    return { ok: true, url: final, format: "mp4", headers: ["User-Agent: " + BrowserUA()] }
end function

' Ok.ru: data-options trae los videos por calidad (y a veces un m3u8).
function ExtractOkru(url as string) as object
    page = Http(url, {})
    q = Chr(34)
    raw = FirstGroup(CreateObject("roRegex", "data-options=" + q + "([^" + q + "]+)" + q, ""), page.body)
    raw = raw.Replace("&quot;", Chr(34)).Replace("&amp;", "&")
    options = ParseJson(raw)
    if options = invalid or options.flashvars = invalid then return { ok: false, error: "Ok.ru no respondió." }
    meta = ParseJson(AsStr(options.flashvars.metadata))
    if meta = invalid then return { ok: false, error: "Ok.ru no respondió." }
    headers = ["Referer: https://ok.ru/", "User-Agent: " + BrowserUA()]
    if AsStr(meta.hlsManifestUrl) <> "" then return { ok: true, url: meta.hlsManifestUrl, format: "hls", headers: headers }
    best = invalid
    for each quality in ["ultra", "quad", "full", "hd", "sd", "low", "lowest", "mobile"]
        if best = invalid and GetInterface(meta.videos, "ifArray") <> invalid then
            for each v in meta.videos
                if best = invalid and AsStr(v.name) = quality and Left(AsStr(v.url), 4) = "http" then best = v.url
            end for
        end if
    end for
    if best = invalid then return { ok: false, error: "Ok.ru no respondió." }
    return { ok: true, url: best, format: "mp4", headers: headers }
end function
