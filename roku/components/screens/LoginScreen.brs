sub init()
    m.form = m.top.findNode("form")
    m.status = m.top.findNode("status")
    m.spinner = m.top.findNode("spinner")
    m.spinner.poster.uri = "pkg:/images/spinner.png"
    m.spinner.poster.width = 96
    m.spinner.poster.height = 96
    m.form.observeField("buttonSelected", "onFormSelected")
    m.user = ""
    m.pass = ""
    m.loading = false
    m.top.findNode("mac").text = "MAC: " + DeviceMac()
    InitScreen(m.form)
    RenderForm()
end sub

sub onParams()
    ' Logo y fondo de la marca guardados (aparecen al instante, también sin internet).
    logo = RegRead("custom_logo")
    if logo = "" then logo = "pkg:/images/logo.png"
    m.top.findNode("logo").uri = logo
    bg = RegRead("custom_background_tv_url")
    if bg = "" then bg = RegRead("custom_background_url")
    m.top.findNode("background").uri = bg
    m.top.findNode("brand").text = RegRead("brand_name")
    if AsStr(m.top.params.message) <> "" then ShowStatus(m.top.params.message)

    savedUser = RegRead("alpha_token")
    savedPass = RegRead("beta_token")
    if savedUser <> "" and savedPass <> "" then
        m.user = savedUser
        m.pass = savedPass
        RenderForm()
        DoLogin(true)
    end if
end sub

sub RenderForm()
    userText = m.user
    if userText = "" then userText = "—"
    passText = "—"
    if m.pass <> "" then passText = String(Len(m.pass), "•")
    SetButtons(m.form, ["Usuario:  " + userText, "Contraseña:  " + passText, "Iniciar sesión"], m.form.buttonFocused)
end sub

sub onFormSelected()
    if m.loading then return
    index = m.form.buttonSelected
    if index = 0 then
        AskInput("Usuario", m.user, "user")
    else if index = 1 then
        AskInput("Contraseña", m.pass, "pass", true)
    else
        ' Sin usuario ni contraseña: entrada con la MAC vinculada desde el panel.
        if (m.user = "") <> (m.pass = "") then
            ShowStatus("Los campos no pueden estar vacíos")
            return
        end if
        DoLogin(false)
    end if
end sub

sub onResult()
    r = m.top.result
    if r.index <> 0 then return
    if r.tag = "user" then m.user = AsStr(r.text).Trim()
    if r.tag = "pass" then m.pass = AsStr(r.text).Trim()
    RenderForm()
    if r.tag = "user" then m.form.focusButton = 1
    if r.tag = "pass" then m.form.focusButton = 2
    m.form.setFocus(true)
end sub

sub DoLogin(auto as boolean)
    m.loading = true
    m.status.visible = false
    m.spinner.visible = true
    m.spinner.control = "start"
    RunTask("login", { user: m.user, pass: m.pass, auto: auto }, "onLogin")
end sub

sub onLogin(event as object)
    res = TaskResult(event)
    if res = invalid then return
    m.loading = false
    m.spinner.control = "stop"
    m.spinner.visible = false
    if res.ok = true then
        m.top.action = { type: "loginOk" }
    else
        ShowStatus(AsStr(res.message))
        if res.report <> invalid then ReportError(res.report, AsStr(res.detail))
    end if
end sub

sub ShowStatus(text as string)
    m.status.text = text
    m.status.visible = true
end sub
