sub init()
    m.keyboard = m.top.findNode("keyboard")
    m.pinpad = m.top.findNode("pinpad")
    m.buttons = m.top.findNode("buttons")
    SetPills(m.buttons, ["Aceptar", "Cancelar"], "", 0)
    m.buttons.observeField("itemSelected", "onButton")
    InitScreen(m.keyboard)
end sub

sub onParams()
    p = m.top.params
    m.top.findNode("title").text = AsStr(p.title)
    if p.numeric = true then
        m.input = m.pinpad
        m.pinpad.pin = AsStr(p.text)
    else
        m.input = m.keyboard
        m.keyboard.text = AsStr(p.text)
        m.keyboard.textEditBox.secureMode = p.secure = true
    end if
    m.input.visible = true
    FocusOn(m.input)
end sub

function CurrentText() as string
    if m.input.isSameNode(m.pinpad) then return m.pinpad.pin
    return m.keyboard.text
end function

sub Finish(accepted as boolean)
    if accepted then
        m.top.action = { type: "result", tag: AsStr(m.top.params.tag), index: 0, text: CurrentText() }
    else
        m.top.action = { type: "result", tag: AsStr(m.top.params.tag), index: -1, text: "" }
    end if
end sub

sub onButton()
    Finish(m.buttons.itemSelected = 0)
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if not press then return false
    if key = "back" then
        Finish(false)
        return true
    else if key = "play" then
        Finish(true)
        return true
    else if key = "down" and m.input.isInFocusChain() then
        FocusOn(m.buttons)
        return true
    else if key = "up" and m.buttons.isInFocusChain() then
        FocusOn(m.input)
        return true
    end if
    return false
end function
