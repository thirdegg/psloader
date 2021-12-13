package com.thirdegg.psloader

import java.io.DataOutputStream
import java.io.File
import java.net.Socket


class PayloadSender(
    val ip: String,
    val port: Int,
    val file: File
) {

    fun send() {
        val socket = Socket(ip, port)
        val outputStream = DataOutputStream(socket.getOutputStream())
        outputStream.write(file.readBytes())
        socket.close()
    }

}