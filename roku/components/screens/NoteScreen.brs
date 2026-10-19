sub init()
    m.text = m.top.findNode("text")
    InitScreen(m.text)
end sub

sub onParams()
    p = m.top.params
    m.top.findNode("title").text = AsStr(p.title)
    m.text.text = AsStr(p.text)
    logo = RegRead("custom_logo")
    if logo = "" then logo = "pkg:/images/logo.png"
    m.top.findNode("logo").uri = logo
    FocusOn(m.text)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and (key = "back" or key = "OK") then
        m.top.action = { type: "back" }
        return true
    end if
    return false
end function
