sub init()
    m.bg = m.top.findNode("bg")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    SetText(m.top.findNode("title"), c.title)
    m.top.findNode("meta").text = c.ShortDescriptionLine1
    SetText(m.top.findNode("overview"), c.description)
    m.top.findNode("still").uri = c.HDPosterUrl
    progress = c.progress
    m.top.findNode("progressBg").visible = progress > 0
    bar = m.top.findNode("progress")
    bar.visible = progress > 0
    if progress > 100 then progress = 100
    bar.width = 300 * progress / 100
end sub

sub onFocusPercent()
    if m.top.focusPercent > 0.5 then m.bg.color = "0x3A3F55FF" else m.bg.color = "0x1A1D29FF"
end sub
