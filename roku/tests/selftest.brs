' Pruebas de la lógica sin interfaz (se ejecutan fuera del Roku con el intérprete "brs"):
'   npx brs tests/selftest.brs source/Utils.brs source/Live.brs source/Api.brs source/Catalog.brs source/Playback.brs
' No se incluye en el paquete del canal.

sub Main()
    m.failures = 0

    ' PanelApiUrl/DecryptData usan roByteArray, que "brs" no tiene: se comprueban en el Roku.
    Check("safeName", SafeName("TMDb (es)"), "TMDb__es_")
    Check("lines", PanelLines("https://a.tv/1.m3u, https://a.tv/1.m3u" + Chr(10) + "ftp://x https://b.tv/2").Join("|"), "https://a.tv/1.m3u|https://b.tv/2")
    Check("rating", Rating1(7.36), "7.4")
    Check("subs", SubBefore(SubAfter("window.video = {id: '123', x", "id: '"), "',"), "123")
    Check("format", GuessFormat("http://x/live/a/b/1.m3u8?t=1"), "hls")

    epg = []
    q = Chr(34)
    m3u = "#EXTM3U url-tvg=" + q + "http://g.tv/epg.xml" + q + Chr(10)
    m3u = m3u + "#EXTINF:-1 tvg-id=" + q + "espn.mx" + q + " tvg-logo=" + q + "http://l/espn.png" + q + " group-title=" + q + "Sports" + q + ",MX: ESPN HD" + Chr(10) + "http://s/espn.m3u8" + Chr(10)
    m3u = m3u + "#EXTINF:-1 group-title=" + q + "Sports" + q + ",ESPN 2" + Chr(10) + "http://s/espn2.m3u8" + Chr(10)
    m3u = m3u + "#EXTINF:-1 group-title=" + q + "Sports" + q + ",Fox Sports" + Chr(10) + "http://s/fox.m3u8" + Chr(10)
    m3u = m3u + "#EXTINF:-1 group-title=" + q + "XXX Adultos" + q + ",Canal X" + Chr(10) + "http://s/x.m3u8" + Chr(10)
    list = CleanChannels(ParseM3u(m3u, epg))
    Check("m3u count (sin adultos)", list.Count().ToStr(), "3")
    Check("m3u nombre sin país", list[0].name, "ESPN HD")
    Check("m3u país", list[0].country, "México")
    Check("m3u grupo", list[0].group, "Deportes")
    Check("m3u epg", epg.Join(","), "http://g.tv/epg.xml")
    Check("key", ChannelKey("ESPN (720p) HD"), "espn")

    other = CleanChannels(ParseM3u("#EXTM3U" + Chr(10) + "#EXTINF:-1,ESPN" + Chr(10) + "http://o/espn.m3u8" + Chr(10) + "#EXTINF:-1,ESPN 3" + Chr(10) + "http://o/espn3.m3u8" + Chr(10), []))
    merged = MergeLines([list, other])
    ' ESPN ya está en la base; ESPN 3 se suma a la categoría de la marca.
    Check("merge count", merged.Count().ToStr(), "4")
    Check("merge grupo por marca", merged[3].group, "Deportes")

    Check("html", HtmlToText("<html><style>p{}</style><body><p>Tu cuenta vence en <b>3</b> días</p><button>Cerrar</button></body></html>"), "Tu cuenta vence en 3 días")

    ' Audio: latino antes que castellano; sin español -> subtítulos completos en español.
    tracks = [{ Track: "1", Language: "eng", Name: "English" }, { Track: "2", Language: "spa", Name: "Español (Castellano)" }, { Track: "3", Language: "spa", Name: "Español (Latino)" }]
    Check("audio latino", PickAudio(tracks, "es").Track, "3")
    Check("audio inglés", PickAudio(tracks, "en").Track, "1")
    Check("sin audio español", AsStr(PickAudio([{ Track: "1", Language: "ita", Name: "Italiano" }], "es")), "")
    ' Sin español: inglés, nunca italiano (aunque venga primero).
    noSpanish = [{ Track: "1", Language: "ita", Name: "Italiano" }, { Track: "2", Language: "eng", Name: "English" }]
    Check("sin español -> inglés", PickAudio(noSpanish, "es").Track, "2")
    Check("castellano antes que inglés", PickAudio([tracks[0], tracks[1]], "es").Track, "2")
    menu = SortAudioTracks([{ Track: "i", Language: "ita" }, tracks[0], tracks[1], tracks[2]])
    Check("menú audio", menu[0].Track + menu[1].Track + menu[2].Track + menu[3].Track, "321i")
    Check("servidor latino", ServerLabel(0, { lang: "lat" }), "Servidor 1 · Latino")
    Check("servidor vix", ServerLabel(4, { lang: "multi" }), "Servidor 5 · Multiaudio")
    subs = [{ TrackName: "a", Language: "spa", Description: "Spanish Forced" }, { TrackName: "b", Language: "spa", Description: "Spanish" }]
    Check("subtítulo completo", PickSubtitle(subs).TrackName, "b")

    ' Nombres de pistas para el menú del botón *.
    Check("pista latino", TrackLabel("spa", "Spanish (Latino)"), "Español latino")
    Check("pista es-419", TrackLabel("es-419", ""), "Español latino")
    Check("pista castellano", TrackLabel("spa", "Castellano"), "Español (España)")
    Check("pista inglés", TrackLabel("eng", "English"), "Inglés")
    Check("pista sin código", TrackLabel("", "Audio 2"), "Audio 2")

    if m.failures = 0 then print "OK: todas las pruebas pasaron" else print "FALLARON " + m.failures.ToStr() + " pruebas"
end sub

sub Check(name as string, actual as dynamic, expected as dynamic)
    if actual <> expected then
        m.failures = m.failures + 1
        print "FALLA " + name + ": " + AsStr(actual) + " <> " + AsStr(expected)
    end if
end sub
