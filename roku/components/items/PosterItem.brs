sub init()
    m.card = m.top.findNode("card")
    m.frame = m.top.findNode("frame")
    m.poster = m.top.findNode("poster")
    m.fallback = m.top.findNode("fallback")
    m.mask = m.top.findNode("mask")
    m.ring = m.top.findNode("ring")
    m.title = m.top.findNode("title")
    m.progressBg = m.top.findNode("progressBg")
    m.progress = m.top.findNode("progress")
    m.poster.observeField("loadStatus", "onLoadStatus")
end sub

sub onContent()
    c = m.top.itemContent
    if c = invalid then return
    m.title.text = c.title
    m.fallback.text = c.title
    m.poster.uri = c.HDPosterUrl
    m.fallback.visible = c.HDPosterUrl = ""
    ' Logos de canales: se ven completos; carátulas: llenan la tarjeta.
    if c.mediaType = "channel" then m.poster.loadDisplayMode = "scaleToFit" else m.poster.loadDisplayMode = "scaleToZoom"
    onLayout()
end sub

sub onLoadStatus()
    m.fallback.visible = m.poster.loadStatus = "failed" or m.poster.uri = ""
end sub

sub onLayout()
    w = m.top.width
    h = m.top.height
    if w <= 0 or h <= 0 then return
    imageH = h - 44
    m.frame.width = w
    m.frame.height = imageH
    m.mask.width = w
    m.mask.height = imageH
    m.ring.width = w
    m.ring.height = imageH
    pad = 0
    c = m.top.itemContent
    if c <> invalid and c.mediaType = "channel" then pad = 14
    m.poster.translation = [pad, pad]
    m.poster.width = w - pad * 2
    m.poster.height = imageH - pad * 2
    m.fallback.width = w - 20
    m.fallback.height = imageH
    m.fallback.translation = [10, 0]
    m.title.width = w
    m.title.translation = [0, imageH + 10]
    m.card.scaleRotateCenter = [w / 2, imageH / 2]
    progress = 0
    if c <> invalid then progress = c.progress
    m.progressBg.visible = progress > 0
    m.progress.visible = progress > 0
    m.progressBg.width = w
    m.progressBg.translation = [0, imageH - 8]
    m.progress.translation = [0, imageH - 8]
    if progress > 100 then progress = 100
    m.progress.width = w * progress / 100
end sub

sub onFocusPercent()
    p = m.top.focusPercent
    if not (m.top.rowListHasFocus or m.top.gridHasFocus) then p = 0
    m.ring.opacity = p
    ' Solo las filas agrandan la tarjeta: en las cuadrículas el borde se recortaría.
    s = 1
    if m.top.rowListHasFocus then s = 1 + 0.05 * p
    m.card.scale = [s, s]
    m.title.opacity = 0.7 + 0.3 * p
end sub
