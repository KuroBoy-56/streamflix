' Pruebas de la nube compartida con Android (simulador brs-engine, no va en el paquete):
'   cat tests/cloud_test.brs source/Utils.brs source/UserData.brs > /tmp/c.brs && npx -p brs-node brs-cli -n /tmp/c.brs

sub Main()
    m.failures = 0
    RegWrite("default_provider", "Cuevana 3")
    Check("clave principal", MainCloudKey(), "Cuevana_3")
    Check("clave Poseidon", CloudKeyFor("PoseidonHD2"), "Poseidonhd2")

    ' Documento como lo sube Android (proveedor Cuevana 3, ids del sitio).
    doc = UD_Normalize({
        Cuevana_3: {
            movies: { "ver-pelicula/matrix": { id: "ver-pelicula/matrix", title: "Matrix", isFavorite: true, favoritedAtMillis: 1700000000000 } }
            tv_shows: { "ver-serie/the-boys": { id: "ver-serie/the-boys", title: "The Boys", lastPlayedAtMillis: 1700000000001, isWatching: true } }
            episodes: { "episodio/the-boys-temporada-1-episodio-2": { id: "episodio/the-boys-temporada-1-episodio-2", number: 2, tvShow: { id: "ver-serie/the-boys" }, season: { number: 1 }, watchHistory: { lastPlaybackPositionMillis: 600000, durationMillis: 3600000, lastEngagementTimeUtcMillis: 1700000000001 } } }
        }
        _profile: { name: "x" }
    })
    Check("sin _profile", doc.DoesExist("_profile").ToStr(), "false")
    links = { Cuevana_3: { style: "cuevana", id: "ver-serie/the-boys", slug: "the-boys" } }

    ' Película de TMDb sin enlaces: se encuentra el registro de Android por el título.
    loc = UD_Locate(doc, "movies", { itemId: "603", title: "Matrix", extra: {} }, invalid)
    Check("película por título", loc.key + " " + loc.id, "Cuevana_3 ver-pelicula/matrix")

    ' Serie con enlaces: registro de Android y episodio existente (serie + temporada + número).
    show = UD_Locate(doc, "tv_shows", { itemId: "76479", title: "The Boys", extra: {} }, links)
    Check("serie por enlace", show.id, "ver-serie/the-boys")
    ep = UD_LocateEpisode(doc, show, 1, 2, "1234")
    Check("episodio existente", ep.id, "episodio/the-boys-temporada-1-episodio-2")
    ep3 = UD_LocateEpisode(doc, show, 1, 3, "1235")
    Check("episodio nuevo (formato Cuevana)", ep3.id, "episodio/the-boys-temporada-1-episodio-3")
    Check("resume", UD_ResumeOf(doc[ep.key].episodes[ep.id]).ToStr(), "600")

    ' Título nuevo en Roku con enlace al proveedor principal: va con el id del sitio.
    nuevo = UD_Locate(doc, "movies", { itemId: "999", title: "Otra", extra: {} }, { Cuevana_3: { style: "cuevana", id: "ver-pelicula/otra", slug: "otra" } })
    Check("nuevo en proveedor principal", nuevo.key + " " + nuevo.id, "Cuevana_3 ver-pelicula/otra")
    ' Sin enlace: va a la clave TMDb.
    sinEnlace = UD_Locate(doc, "movies", { itemId: "998", title: "Nada", extra: {} }, invalid)
    Check("nuevo sin enlace", sinEnlace.key + " " + sinEnlace.id, "TMDb__es_ 998")

    Check("temporada Poseidon", LinkSeasonId({ style: "poseidon", id: "152483/the-boys" }, "2"), "152483/the-boys/temporada/2")
    Check("episodio CineCalidad", LinkEpisodeId({ style: "cine", id: "x", slug: "the-boys", base: "https://www.cinecalidad.am" }, "1", "2"), "https://www.cinecalidad.am/ver-el-episodio/the-boys-1x2/")
    ' El JSON que se sube lleva los nombres exactos que lee Android (Gson distingue mayúsculas).
    rec = UD_BaseRecord("movies", { title: "Matrix", rating: "7.4" }, "ver-pelicula/matrix")
    JSet(rec, "lastPlayedAtMillis", 5)
    UD_ApplyWatch(rec, false, JObj([["lastPlaybackPositionMillis", 1000]]))
    json = FormatJson(rec)
    for each field in ["isFavorite", "isWatched", "lastPlayedAtMillis", "watchHistory", "lastPlaybackPositionMillis"]
        Check("campo " + field, (Instr(1, json, Chr(34) + field + Chr(34)) > 0).ToStr(), "true")
    end for
    UD_ApplyWatch(rec, true, invalid)
    Check("visto con fecha", (Instr(1, FormatJson(rec), Chr(34) + "dayOfMonth" + Chr(34)) > 0).ToStr(), "true")
    RegClearSession()
    if m.failures = 0 then print "OK: nube" else print "FALLARON " + m.failures.ToStr()
end sub

sub Check(name as string, actual as dynamic, expected as dynamic)
    if actual <> expected then
        m.failures = m.failures + 1
        print "FALLA " + name + ": " + AsStr(actual) + " <> " + AsStr(expected)
    end if
end sub
