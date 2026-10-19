sub init()
    m.top.functionName = "execute"
end sub

sub execute()
    req = m.top.request
    p = req.params
    if p = invalid then p = {}
    kind = req.kind
    res = invalid
    if kind = "login" then
        res = ApiLogin(p)
    else if kind = "security" then
        res = ApiSecurity()
    else if kind = "branding" then
        res = ApiBranding()
    else if kind = "notice" then
        res = ApiNotice()
    else if kind = "profiles" then
        res = ApiProfiles(p.body)
    else if kind = "syncDown" then
        res = ApiSyncDown(p.profileId)
    else if kind = "syncUp" then
        res = ApiSyncUp(p.profileId, p.data)
    else if kind = "report" then
        res = ApiReport(p)
    else if kind = "home" then
        res = ApiHome()
    else if kind = "catalog" then
        res = ApiCatalog(p.mediaType)
    else if kind = "page" then
        res = ApiPage(p)
    else if kind = "details" then
        res = ApiDetails(p)
    else if kind = "season" then
        res = ApiSeason(p)
    else if kind = "search" then
        res = ApiSearch(p)
    else if kind = "person" then
        res = ApiPerson(p)
    else if kind = "resolve" then
        res = ApiResolve(p)
    else if kind = "links" then
        res = ApiLinks(p)
    else if kind = "servers" then
        res = ApiServers(p)
    else if kind = "extract" then
        res = ApiExtract(p)
    else if kind = "live" then
        res = ApiLive(p)
    else if kind = "epg" then
        res = ApiEpg(p)
    end if
    if res = invalid then res = { error: "Petición desconocida: " + AsStr(kind) }
    res.kind = kind
    res.request = p
    m.top.response = res
end sub
