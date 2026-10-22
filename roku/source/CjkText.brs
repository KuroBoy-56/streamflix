' Coreano, japonés y chino: las fuentes del Roku no traen esas letras y salen cuadros. Una
' etiqueta con esas letras usa una fuente incluida en el canal (Noto Sans, recortada a los
' caracteres de uso común) del mismo tamaño; con texto normal vuelve a su fuente original.
' shortcut: solo los ~7.000 ideogramas más usados (GB2312 nivel 1, JIS nivel 1, Big5 nivel 1)
' y los 2.350 hangul comunes; un carácter muy raro aún sale como cuadro.

' "" (texto latino), "ko" (hangul) o "han" (kanji/hanzi/kana).
function CjkScript(text as string) as string
    found = ""
    for i = 1 to Len(text)
        c = Asc(Mid(text, i, 1))
        if (c >= &hAC00 and c <= &hD7A3) or (c >= &h1100 and c <= &h11FF) or (c >= &h3130 and c <= &h318F) then return "ko"
        if (c >= &h3040 and c <= &h30FF) or (c >= &h3400 and c <= &h9FFF) or (c >= &hF900 and c <= &hFAFF) then found = "han"
    end for
    return found
end function

sub SetText(label as object, text as dynamic)
    value = ""
    if text <> invalid then value = text.ToStr()
    if label.baseFont = invalid then label.addFields({ baseFont: label.font, cjk: "" })
    script = CjkScript(value)
    if script <> label.cjk then
        if script = "" then
            label.font = label.baseFont
        else
            font = CreateObject("roSGNode", "Font")
            font.uri = "pkg:/fonts/cjk_" + script + ".otf"
            font.size = label.baseFont.size
            if font.size <= 0 then font.size = 28
            label.font = font
        end if
        label.cjk = script
    end if
    label.text = value
end sub
