sub init()
    m.buttons = m.top.findNode("buttons")
    m.buttons.observeField("buttonSelected", "onSelected")
    InitScreen(m.buttons)
end sub

sub onParams()
    p = m.top.params
    m.top.findNode("title").text = AsStr(p.title)
    m.top.findNode("message").text = AsStr(p.message)
    buttons = p.buttons
    if buttons = invalid or buttons.Count() = 0 then buttons = ["Aceptar"]
    m.buttons.buttons = buttons
    FocusOn(m.buttons)
end sub

sub onSelected()
    index = m.buttons.buttonSelected
    m.top.action = { type: "result", tag: AsStr(m.top.params.tag), index: index, button: m.buttons.buttons[index] }
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and key = "back" then
        m.top.action = { type: "result", tag: AsStr(m.top.params.tag), index: -1 }
        return true
    end if
    return false
end function
