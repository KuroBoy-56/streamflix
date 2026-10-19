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
    ' Más de dos opciones no caben en fila: van en columna y la tarjeta crece.
    messageHeight = 250
    if buttons.Count() > 2 then
        messageHeight = 110
        m.buttons.layoutDirection = "vert"
        m.buttons.itemSpacings = [12]
        m.buttons.minWidth = 880
        m.buttons.maxWidth = 880
        m.buttons.buttonHeight = 72
        height = 130 + messageHeight + 20 + buttons.Count() * 84 + 40
    else
        height = 560
    end if
    m.top.findNode("message").height = messageHeight
    m.buttons.translation = [60, 130 + messageHeight + 20]
    panel = m.top.findNode("panel")
    panel.height = height
    panel.translation = [460, (1080 - height) / 2]
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
