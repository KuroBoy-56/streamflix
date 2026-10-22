' Pruebas del extractor (desempaquetado P.A.C.K.E.R. y cifrado de Voe) con el simulador
' brs-engine, que sí tiene roByteArray (no se incluye en el paquete del canal):
'   cat tests/extract_test.brs source/Utils.brs source/Live.brs source/Playback.brs source/Sources.brs source/Extract.brs source/Api.brs source/Catalog.brs > /tmp/t.brs
'   npx -p brs-node brs-cli -n /tmp/t.brs

sub Main()
    m.failures = 0
    packed = "eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0 1=\'2\';3.4({5:" + Chr(34) + "6\/7.8" + Chr(34) + "})',36,9,'var|x|hello|jwplayer|setup|file|https:\/\/cdn.example\/hls2|master|m3u8'.split('|'),0,{}))"
    out = UnpackJs(packed)
    Check("unpack", out, "var x='hello';jwplayer.setup({file:" + Chr(34) + "https:\/\/cdn.example\/hls2\/master.m3u8" + Chr(34) + "})")
    Check("generic file", VideoResult(FirstGroup(CreateObject("roRegex", "file" + Chr(34) + "?\s*:\s*" + Chr(34) + "([^" + Chr(34) + "]+)", ""), out), "https://host.tv/e/1").url, "https://cdn.example/hls2/master.m3u8")
    voe = VoeDecrypt("CR1TH@$wEEI2_I4GSgqZ1SoKUp8Iy12EIgyn2goKKt3Fy1gCUkCBIOYMwAIF2EfETkFoSt1KUkMAzI9GKkb")
    if voe = invalid then Check("voe", "invalid", "json") else Check("voe", voe.source, "https://cdn.example/master.m3u8")
    Check("host", HostOf("https://Voe.SX/e/abc"), "voe.sx")
    Check("unsupported", Unsupported("Cuevana · filemoon").ToStr(), "true")
    if m.failures = 0 then print "OK: extractor" else print "FALLARON " + m.failures.ToStr()
end sub

sub Check(name as string, actual as dynamic, expected as dynamic)
    if actual <> expected then
        m.failures = m.failures + 1
        print "FALLA " + name + ": " + AsStr(actual) + " <> " + AsStr(expected)
    end if
end sub
