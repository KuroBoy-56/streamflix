' Utilidades compartidas por la interfaz y las tareas (equivalen a UserPreferences,
' DeviceMac, CloudSyncManager.decryptData y generateSecurityToken de la app Android).

function AppVersionName() as string
    return "1.8.9"
end function

' Mismo versionCode que la app Android: el panel decide con él si hay actualización obligatoria.
function AppVersionCode() as integer
    return 21
end function

' Carpeta de la API del panel y de las imágenes de marca (ofuscadas igual que en Android).
function PanelApiUrl(file as string) as string
    return DecryptData("4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d7a386965673d3d") + file
end function

function PanelConfigUrl(file as string) as string
    return DecryptData("4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d53416c4d7a736f4644772f4b47513d") + file
end function

' hex -> base64 -> XOR "KURO" (mismo esquema que la app Android).
function DecryptData(hexData as string) as string
    raw = CreateObject("roByteArray")
    raw.FromHexString(hexData)
    data = CreateObject("roByteArray")
    data.FromBase64String(raw.ToAsciiString())
    key = [75, 85, 82, 79]
    out = CreateObject("roByteArray")
    for i = 0 to data.Count() - 1
        a = data[i]
        b = key[i mod 4]
        out.Push(((a or b) and not (a and b)) and 255)
    end for
    return out.ToAsciiString()
end function

' ---------------------------------------------------------------------------------------
' Preferencias (registro del canal: equivalente a SharedPreferences "SecureGatewayPrefs")
' ---------------------------------------------------------------------------------------

function RegRead(key as string, def = "" as string) as string
    sec = CreateObject("roRegistrySection", "gateway")
    if sec.Exists(key) then return sec.Read(key)
    return def
end function

sub RegWrite(key as string, value as string)
    sec = CreateObject("roRegistrySection", "gateway")
    sec.Write(key, value)
    sec.Flush()
end sub

sub RegWriteMany(values as object)
    sec = CreateObject("roRegistrySection", "gateway")
    for each key in values
        sec.Write(key, AsStr(values[key]))
    end for
    sec.Flush()
end sub

' Cerrar sesión: se borra todo menos el dueño del equipo (para saber si entra otra cuenta).
sub RegClearSession()
    sec = CreateObject("roRegistrySection", "gateway")
    for each key in sec.GetKeyList()
        if key <> "device_owner" then sec.Delete(key)
    end for
    sec.Flush()
end sub

function IsLoggedIn() as boolean
    return RegRead("alpha_token") <> "" and RegRead("beta_token") <> ""
end function

' ---------------------------------------------------------------------------------------
' Equipo y seguridad
' ---------------------------------------------------------------------------------------

' "MAC" del equipo como la registra el panel: 16 dígitos hex en pares (AA:BB:...).
' En Roku se toma del identificador único del canal en este equipo.
function DeviceMac() as string
    raw = LCase(CreateObject("roDeviceInfo").GetChannelClientId())
    clean = ""
    for i = 1 to Len(raw)
        c = Mid(raw, i, 1)
        if Instr(1, "0123456789abcdef", c) > 0 then clean = clean + c
    end for
    while Len(clean) < 16
        clean = clean + "0"
    end while
    clean = UCase(Left(clean, 16))
    parts = []
    for i = 0 to 7
        parts.Push(Mid(clean, i * 2 + 1, 2))
    end for
    return parts.Join(":")
end function

function DeviceModel() as string
    di = CreateObject("roDeviceInfo")
    return "Roku " + di.GetModelDisplayName() + " (" + di.GetModel() + ", Roku OS " + AsStr(di.GetOSVersion().major) + ")"
end function

function Sha256Hex(text as string) as string
    ba = CreateObject("roByteArray")
    ba.FromAsciiString(text)
    digest = CreateObject("roEVPDigest")
    digest.Setup("sha256")
    return LCase(digest.Process(ba))
end function

' Hora de Panamá (UTC-5, sin horario de verano) en formato yyyy-MM-dd-HH.
function PanamaHour() as string
    dt = CreateObject("roDateTime")
    dt.FromSeconds(dt.AsSeconds() - 5 * 3600)
    return dt.GetYear().ToStr() + "-" + Pad2(dt.GetMonth()) + "-" + Pad2(dt.GetDayOfMonth()) + "-" + Pad2(dt.GetHours())
end function

' Token que valida el panel: sha256(usuario + mac + hora de Panamá + clave).
function SecurityToken(username as string, mac as string) as string
    return Sha256Hex(username + mac + PanamaHour() + "kuropanchi950125")
end function

' ---------------------------------------------------------------------------------------
' Tiempo
' ---------------------------------------------------------------------------------------

function NowSec() as integer
    return CreateObject("roDateTime").AsSeconds()
end function

function NowMs() as longinteger
    dt = CreateObject("roDateTime")
    secs& = dt.AsSeconds()
    return secs& * 1000 + dt.GetMilliseconds()
end function

function Pad2(n as integer) as string
    s = n.ToStr()
    if Len(s) < 2 then s = "0" + s
    return s
end function

' "1:05:09" o "05:09".
function FormatClock(totalSeconds as integer) as string
    if totalSeconds < 0 then totalSeconds = 0
    h = totalSeconds \ 3600
    mnt = (totalSeconds mod 3600) \ 60
    s = totalSeconds mod 60
    if h > 0 then return h.ToStr() + ":" + Pad2(mnt) + ":" + Pad2(s)
    return Pad2(mnt) + ":" + Pad2(s)
end function

' ---------------------------------------------------------------------------------------
' Conversión de tipos (los JSON del panel mezclan números y textos)
' ---------------------------------------------------------------------------------------

function IsStr(v as dynamic) as boolean
    return GetInterface(v, "ifString") <> invalid
end function

function IsNum(v as dynamic) as boolean
    return GetInterface(v, "ifInt") <> invalid or GetInterface(v, "ifFloat") <> invalid or GetInterface(v, "ifDouble") <> invalid or GetInterface(v, "ifLongInt") <> invalid
end function

function ToInt(v as dynamic, def = 0 as integer) as integer
    if IsStr(v) then
        if v.Trim() = "" then return def
        return v.Trim().ToInt()
    end if
    if IsNum(v) then return Int(v)
    if GetInterface(v, "ifBoolean") <> invalid then
        if v then return 1
        return 0
    end if
    return def
end function

function ToLong(v as dynamic) as longinteger
    result& = 0
    if IsStr(v) then
        if v.Trim() <> "" then result& = ParseJson(v.Trim())
    else if IsNum(v) then
        result& = v
    end if
    return result&
end function

function ToFloat(v as dynamic) as float
    if IsStr(v) then return v.ToFloat()
    if IsNum(v) then return v
    return 0
end function

function AsStr(v as dynamic) as string
    if v = invalid then return ""
    if IsStr(v) then return v
    if GetInterface(v, "ifBoolean") <> invalid then
        if v then return "true"
        return "false"
    end if
    if IsNum(v) then
        if GetInterface(v, "ifInt") <> invalid or GetInterface(v, "ifLongInt") <> invalid then return v.ToStr()
        if v = Int(v) then return Int(v).ToStr()
        return Str(v).Trim()
    end if
    return ""
end function

' Texto del panel: Android hace .replace("null", "").trim().
function PanelText(v as dynamic) as string
    return AsStr(v).Replace("null", "").Trim()
end function

' Minúsculas sin acentos ("Música" -> "musica").
function Normalize(value as string) as string
    s = LCase(value)
    if m.reNonAscii = invalid then m.reNonAscii = CreateObject("roRegex", "[^\x00-\x7F]", "")
    if not m.reNonAscii.IsMatch(s) then return s.Trim()
    pairs = [["á", "a"], ["à", "a"], ["ä", "a"], ["â", "a"], ["ã", "a"], ["é", "e"], ["è", "e"], ["ë", "e"], ["ê", "e"], ["í", "i"], ["ì", "i"], ["ï", "i"], ["î", "i"], ["ó", "o"], ["ò", "o"], ["ö", "o"], ["ô", "o"], ["õ", "o"], ["ú", "u"], ["ù", "u"], ["ü", "u"], ["û", "u"], ["ñ", "n"], ["ç", "c"], ["Á", "a"], ["É", "e"], ["Í", "i"], ["Ó", "o"], ["Ú", "u"], ["Ñ", "n"]]
    for each p in pairs
        s = s.Replace(p[0], p[1])
    end for
    return s.Trim()
end function

function StartsWithI(text as string, prefix as string) as boolean
    return LCase(Left(text, Len(prefix))) = LCase(prefix)
end function

function IsHttpUrl(text as string) as boolean
    return StartsWithI(text, "http://") or StartsWithI(text, "https://")
end function

' Clave segura para la nube: Android reemplaza todo lo que no sea letra o número por "_".
function SafeName(name as string) as string
    re = CreateObject("roRegex", "[^a-zA-Z0-9]", "")
    return re.ReplaceAll(name, "_")
end function

' Lista de URLs de canales: arreglo JSON o texto con una URL por línea (PanelChannelConfig).
function PanelLines(value as dynamic) as object
    raw = []
    if GetInterface(value, "ifArray") <> invalid then
        for each v in value
            raw.Push(AsStr(v))
        end for
    else if IsStr(value) then
        raw = CreateObject("roRegex", "[\n\r, ]+", "").Split(value)
    end if
    out = []
    seen = {}
    for each line in raw
        t = line.Trim()
        if IsHttpUrl(t) and not seen.DoesExist(t) then
            seen[t] = true
            out.Push(t)
        end if
    end for
    return out
end function

function SplitLines(text as string) as object
    out = []
    for each line in text.Split(Chr(10))
        t = line.Trim()
        if t <> "" then out.Push(t)
    end for
    return out
end function

' Idioma del catálogo TMDb: el del proveedor por defecto del panel ("TMDb (es)") o español.
function CatalogLanguage() as string
    def = LCase(RegRead("default_provider"))
    re = CreateObject("roRegex", "tmdb\s*\(([a-z]{2})\)", "i")
    found = re.Match(def)
    if found.Count() > 1 then return LCase(found[1])
    return "es"
end function

function CatalogProviderName() as string
    return "TMDb (" + CatalogLanguage() + ")"
end function

function TmdbImage(path as dynamic, size as string) as string
    if path = invalid or AsStr(path) = "" then return ""
    return "https://image.tmdb.org/t/p/" + size + AsStr(path)
end function
