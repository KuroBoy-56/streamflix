sub init()
    m.bg = m.top.findNode("bg")
    m.label = m.top.findNode("label")
end sub

sub onContent()
    if m.top.itemContent <> invalid then m.label.text = m.top.itemContent.title
end sub

sub onLayout()
    m.bg.width = m.top.width
    m.bg.height = m.top.height
    m.label.width = m.top.width - 56
    m.label.height = m.top.height
end sub

sub onFocus()
    p = m.top.focusPercent
    if m.top.listHasFocus then
        m.bg.uri = "pkg:/images/menu_focus.9.png"
    else
        m.bg.uri = "pkg:/images/menu_idle.9.png"
    end if
    m.bg.opacity = p
    if p > 0.5 then m.label.color = "0xFFFFFFFF" else m.label.color = "0xB4B9C8FF"
end sub
