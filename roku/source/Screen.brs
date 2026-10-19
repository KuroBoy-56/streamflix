' Foco de cada pantalla: al volver a ella (Atrás) el foco regresa al último elemento usado.
sub InitScreen(defaultFocus as object)
    m.focusTarget = defaultFocus
    m.top.observeField("focusedChild", "onScreenFocus")
end sub

sub onScreenFocus()
    if m.top.hasFocus() and m.focusTarget <> invalid then m.focusTarget.setFocus(true)
end sub

sub FocusOn(node as object)
    m.focusTarget = node
    node.setFocus(true)
end sub

' Cambia el elemento enfocado solo si la pantalla tiene el foco (no lo roba al menú).
sub FocusSoft(node as object)
    m.focusTarget = node
    if m.top.isInFocusChain() then node.setFocus(true)
end sub
