' Ejecuta una petición en segundo plano (ApiTask) y llama a `callback` con el resultado.
' Solo cuenta la última petición de cada `key`: una respuesta vieja se descarta en TaskResult.
function RunTask(kind as string, params as object, callback as string, key = "" as string) as object
    if key = "" then key = kind
    task = CreateObject("roSGNode", "ApiTask")
    task.request = { kind: kind, key: key, params: params }
    task.observeField("response", callback)
    if m.tasks = invalid then m.tasks = {}
    m.tasks[key] = task
    task.control = "RUN"
    return task
end function

function TaskResult(event as object) as dynamic
    task = event.getRoSGNode()
    key = task.request.key
    if m.tasks = invalid or m.tasks[key] = invalid then return invalid
    if not m.tasks[key].isSameNode(task) then return invalid
    m.tasks.Delete(key)
    return event.getData()
end function

' Las pantallas avisan a MainScene por su campo "action".
sub SendAction(action as object)
    m.top.action = action
end sub

sub OpenScreen(name as string, params = {} as object)
    SendAction({ type: "open", screen: name, params: params })
end sub

sub ShowMessage(title as string, message as string, buttons = ["Aceptar"] as object, tag = "" as string)
    OpenScreen("MessageScreen", { title: title, message: message, buttons: buttons, tag: tag })
end sub

sub AskInput(title as string, text as string, tag as string, secure = false as boolean, numeric = false as boolean)
    OpenScreen("InputScreen", { title: title, text: text, tag: tag, secure: secure, numeric: numeric })
end sub

sub ReportError(kind as string, message as string)
    RunTask("report", { tipo: kind, mensaje: message }, "onReportDone", "report" + NowSec().ToStr() + Rnd(100000).ToStr())
end sub

sub onReportDone(event as object)
    TaskResult(event)
end sub
