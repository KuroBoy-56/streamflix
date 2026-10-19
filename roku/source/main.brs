sub Main(args as dynamic)
    screen = CreateObject("roSGScreen")
    port = CreateObject("roMessagePort")
    screen.setMessagePort(port)

    ' Estado compartido entre pantallas y tareas (perfil activo, historial/favoritos).
    m.global = screen.getGlobalNode()
    m.global.addFields({ profileId: "00", userData: {}, userDataTick: 0, uploadPending: false, brandTick: 0 })

    scene = screen.CreateScene("MainScene")
    screen.show()
    scene.observeField("exitApp", port)

    while true
        msg = wait(0, port)
        msgType = type(msg)
        if msgType = "roSGScreenEvent"
            if msg.isScreenClosed() then return
        else if msgType = "roSGNodeEvent"
            if msg.getField() = "exitApp" and msg.getData() = true then screen.close()
        end if
    end while
end sub
