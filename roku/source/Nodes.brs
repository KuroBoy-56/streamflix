' Elementos del catálogo como ContentNode (filas, cuadrículas) y de vuelta a diccionario.
' Claves de un elemento: itemId, mediaType (movie, tv, person, channel), title, overview,
' poster, backdrop, rating, year, progress (0-100) y extra (datos propios del tipo).

function ItemNode(item as object) as object
    node = CreateObject("roSGNode", "ContentNode")
    node.AddFields({ itemId: "", mediaType: "", backdrop: "", rating: "", year: "", progress: 0, extra: {} })
    node.title = AsStr(item.title)
    node.description = AsStr(item.overview)
    node.HDPosterUrl = AsStr(item.poster)
    node.FHDPosterUrl = AsStr(item.poster)
    node.itemId = AsStr(item.itemId)
    node.mediaType = AsStr(item.mediaType)
    node.backdrop = AsStr(item.backdrop)
    node.rating = AsStr(item.rating)
    node.year = AsStr(item.year)
    node.progress = ToInt(item.progress)
    if item.extra <> invalid then node.extra = item.extra
    return node
end function

function NodeToItem(node as object) as object
    return {
        itemId: node.itemId
        mediaType: node.mediaType
        title: node.title
        overview: node.description
        poster: node.HDPosterUrl
        backdrop: node.backdrop
        rating: node.rating
        year: node.year
        progress: node.progress
        extra: node.extra
    }
end function

' Fila con elementos. `paging` (opcional): { path, params, page } para cargar más al llegar al final.
function RowNode(title as string, items as object, paging = invalid as dynamic) as object
    row = CreateObject("roSGNode", "ContentNode")
    row.title = title
    row.AddFields({ paging: {}, loading: false })
    if paging <> invalid then row.paging = paging
    for each item in items
        row.appendChild(ItemNode(item))
    end for
    return row
end function
