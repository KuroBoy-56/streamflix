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


' Opciones de una lista/cuadrícula de PillItem. Cambiar el contenido no quita el foco.
sub SetPills(list as object, labels as object, style = "" as string, primaryIndex = -1 as integer, posters = invalid as object)
    focused = list.itemFocused
    content = CreateObject("roSGNode", "ContentNode")
    for i = 0 to labels.Count() - 1
        item = content.createChild("ContentNode")
        item.title = labels[i]
        item.ShortDescriptionLine1 = style
        if i = primaryIndex then item.ShortDescriptionLine2 = "primary"
        if posters <> invalid then item.update(posters[i])
    end for
    list.content = content
    if focused > 0 and focused < labels.Count() then list.jumpToItem = focused
end sub
