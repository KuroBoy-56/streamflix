' Contenido: title (texto), ShortDescriptionLine1 = "left" para alinear a la izquierda,
' ShortDescriptionLine2 = "primary" para el botón principal (rojo suave aun sin foco).
sub init()
    m.idle = m.top.findNode("idle")
    m.focus = m.top.findNode("focus")
    m.label = m.top.findNode("label")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.label.text = c.title
    if c.ShortDescriptionLine1 = "left" then m.label.horizAlign = "left" else m.label.horizAlign = "center"
    onLayout()
    onFocus()
end sub

sub onLayout()
    w = m.top.width
    h = m.top.height
    m.idle.width = w
    m.idle.height = h
    m.focus.width = w
    m.focus.height = h
    ' Píldoras estrechas (botones en fila): letra algo menor para que el texto quepa.
    m.small = w < 400
    pad = 32
    if m.small then pad = 18
    if w < 200 then pad = 8
    m.label.translation = [pad, 0]
    m.label.width = w - pad * 2
    m.label.height = h
end sub

sub onFocus()
    p = m.top.focusPercent
    active = m.top.gridHasFocus or m.top.listHasFocus
    if not active then p = 0
    primary = m.top.itemContent <> invalid and m.top.itemContent.ShortDescriptionLine2 = "primary"
    base = 0
    if primary then base = 0.45
    m.focus.opacity = base + (1 - base) * p
    regular = "font:MediumSystemFont"
    bold = "font:MediumBoldSystemFont"
    if m.small = true then
        regular = "font:SmallSystemFont"
        bold = "font:SmallBoldSystemFont"
    end if
    if p > 0.5 then
        m.label.color = "0xFFFFFFFF"
        m.label.font = bold
    else
        m.label.color = "0xD8DCE8FF"
        m.label.font = regular
    end if
end sub
