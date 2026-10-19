sub init()
    m.buttons = m.top.findNode("buttons")
    m.buttons.observeField("itemSelected", "onSelected")
    InitScreen(m.buttons)
end sub

sub onParams()
    p = m.top.params
    m.top.findNode("title").text = AsStr(p.title)
    m.top.findNode("message").text = AsStr(p.message)
    buttons = p.buttons
    if buttons = invalid or buttons.Count() = 0 then buttons = ["Aceptar"]
    m.labels = buttons
    n = buttons.Count()
    ' Hasta dos opciones en fila; más de dos en columna (la tarjeta crece para que quepan).
    if n > 2 then
        messageHeight = 110
        m.buttons.numColumns = 1
        m.buttons.numRows = n
        m.buttons.itemSize = [880, 72]
        m.buttons.itemSpacing = [0, 12]
        gridWidth = 880
        height = 130 + messageHeight + 20 + n * 84 + 40
    else
        ' Alto del mensaje según su largo (aprox. 55 letras por línea).
        text = AsStr(p.message)
        lines = 1 + Len(text) \ 55 + text.Split(Chr(10)).Count() - 1
        messageHeight = lines * 46
        if messageHeight > 400 then messageHeight = 400
        m.buttons.numColumns = n
        m.buttons.numRows = 1
        gridWidth = n * 300 + (n - 1) * 24
        height = 130 + messageHeight + 30 + 80 + 60
    end if
    SetPills(m.buttons, buttons)
    m.top.findNode("message").height = messageHeight
    m.buttons.translation = [(1000 - gridWidth) / 2, 130 + messageHeight + 20]
    panel = m.top.findNode("panel")
    panel.height = height
    panel.translation = [460, (1080 - height) / 2]
    FocusOn(m.buttons)
end sub

sub onSelected()
    index = m.buttons.itemSelected
    m.top.action = { type: "result", tag: AsStr(m.top.params.tag), index: index, button: m.labels[index] }
end sub

function onKeyEvent(key as string, press as boolean) as boolean
    if press and key = "back" then
        m.top.action = { type: "result", tag: AsStr(m.top.params.tag), index: -1 }
        return true
    end if
    return false
end function
