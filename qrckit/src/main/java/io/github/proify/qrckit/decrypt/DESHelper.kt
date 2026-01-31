/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.qrckit.decrypt

object DESHelper {
    const val ENCRYPT = 1
    const val DECRYPT = 0

    private val box1 = intArrayOf(
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
    )

    private val box2 = intArrayOf(
        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 15, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
    )

    private val box3 = intArrayOf(
        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
    )

    private val box4 = intArrayOf(
        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 10, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
    )

    private val box5 = intArrayOf(
        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
    )

    private val box6 = intArrayOf(
        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
    )

    private val box7 = intArrayOf(
        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
    )

    private val box8 = intArrayOf(
        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
    )

    // 密钥调度相关参数
    private val keyRndShift = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
    private val keyPermC = intArrayOf(
        56, 48, 40, 32, 24, 16, 8, 0, 57, 49, 41, 33, 25, 17,
        9, 1, 58, 50, 42, 34, 26, 18, 10, 2, 59, 51, 43, 35
    )
    private val keyPermD = intArrayOf(
        62, 54, 46, 38, 30, 22, 14, 6, 61, 53, 45, 37, 29, 21,
        13, 5, 60, 52, 44, 36, 28, 20, 12, 4, 27, 19, 11, 3
    )
    private val keyCompression = intArrayOf(
        13, 16, 10, 23, 0, 4, 2, 27, 14, 5, 20, 9, 22, 18,
        11, 3, 25, 7, 15, 6, 26, 19, 12, 1, 40, 51, 30, 36,
        46, 54, 29, 39, 50, 44, 32, 47, 43, 48, 38, 55, 33,
        52, 45, 41, 49, 35, 28, 31
    )

    // 位操作辅助函数
    private fun getBitFromByteArray(a: ByteArray, b: Int, c: Int): Int {
        val byteVal = a[(b / 32 * 4 + 3 - b % 32 / 8)].toInt() and 0xFF
        return (byteVal ushr (7 - (b % 8)) and 0x01) shl c
    }

    private fun getBitFromIntR(a: Int, b: Int, c: Int): Int = ((a ushr (31 - b)) and 0x01) shl c
    private fun getBitFromIntL(a: Int, b: Int, c: Int): Int = ((a shl b) and (-0x80000000)) ushr c
    private fun formatSBoxInput(a: Int): Int =
        (a and 0x20) or ((a and 0x1f) ushr 1) or ((a and 0x01) shl 4)

    /**
     * 3DES 密钥设置 (EDE 模式)
     */
    fun tripleDESKeySetup(key: ByteArray, schedule: Array<Array<ByteArray>>, mode: Int) {
        if (mode == ENCRYPT) {
            keySchedule(key, 0, schedule[0], ENCRYPT)  // 加密 K1
            keySchedule(key, 8, schedule[1], DECRYPT)  // 解密 K2
            keySchedule(key, 16, schedule[2], ENCRYPT) // 加密 K3
        } else {
            keySchedule(key, 0, schedule[2], DECRYPT)  // 解密 K3
            keySchedule(key, 8, schedule[1], ENCRYPT)  // 加密 K2
            keySchedule(key, 16, schedule[0], DECRYPT) // 解密 K1
        }
    }

    /**
     * 3DES 执行核心
     */
    fun tripleDESCrypt(input: ByteArray, output: ByteArray, key: Array<Array<ByteArray>>) {
        crypt(input, output, key[0])
        crypt(output, output, key[1])
        crypt(output, output, key[2])
    }

    private fun keySchedule(key: ByteArray, offset: Int, schedule: Array<ByteArray>, mode: Int) {
        var c = 0
        var d = 0
        for (i in 0..<28) {
            c = c or getBitFromByteArray(key, keyPermC[i] + offset * 8, 31 - i)
            d = d or getBitFromByteArray(key, keyPermD[i] + offset * 8, 31 - i)
        }
        for (i in 0..<16) {
            c = ((c shl keyRndShift[i]) or (c ushr (28 - keyRndShift[i]))) and -0x10
            d = ((d shl keyRndShift[i]) or (d ushr (28 - keyRndShift[i]))) and -0x10
            val toGen = if (mode == DECRYPT) 15 - i else i
            schedule[toGen].fill(0)
            for (k in 0..<24) {
                schedule[toGen][k / 8] =
                    (schedule[toGen][k / 8].toInt() or getBitFromIntR(
                        c,
                        keyCompression[k],
                        7 - (k % 8)
                    )).toByte()
            }
            for (k in 24..<48) {
                schedule[toGen][k / 8] =
                    (schedule[toGen][k / 8].toInt() or getBitFromIntR(
                        d,
                        keyCompression[k] - 27,
                        7 - (k % 8)
                    )).toByte()
            }
        }
    }

    private fun initialPermutation(state: IntArray, input: ByteArray) {
        state[0] = getBitFromByteArray(input, 57, 31) or getBitFromByteArray(
            input,
            49,
            30
        ) or getBitFromByteArray(
            input,
            41,
            29
        ) or getBitFromByteArray(input, 33, 28) or
                getBitFromByteArray(input, 25, 27) or getBitFromByteArray(
            input,
            17,
            26
        ) or getBitFromByteArray(
            input,
            9,
            25
        ) or getBitFromByteArray(input, 1, 24) or
                getBitFromByteArray(input, 59, 23) or getBitFromByteArray(
            input,
            51,
            22
        ) or getBitFromByteArray(
            input,
            43,
            21
        ) or getBitFromByteArray(input, 35, 20) or
                getBitFromByteArray(input, 27, 19) or getBitFromByteArray(
            input,
            19,
            18
        ) or getBitFromByteArray(
            input,
            11,
            17
        ) or getBitFromByteArray(input, 3, 16) or
                getBitFromByteArray(input, 61, 15) or getBitFromByteArray(
            input,
            53,
            14
        ) or getBitFromByteArray(
            input,
            45,
            13
        ) or getBitFromByteArray(input, 37, 12) or
                getBitFromByteArray(input, 29, 11) or getBitFromByteArray(
            input,
            21,
            10
        ) or getBitFromByteArray(
            input,
            13,
            9
        ) or getBitFromByteArray(input, 5, 8) or
                getBitFromByteArray(input, 63, 7) or getBitFromByteArray(
            input,
            55,
            6
        ) or getBitFromByteArray(
            input,
            47,
            5
        ) or getBitFromByteArray(input, 39, 4) or
                getBitFromByteArray(input, 31, 3) or getBitFromByteArray(
            input,
            23,
            2
        ) or getBitFromByteArray(
            input,
            15,
            1
        ) or getBitFromByteArray(input, 7, 0)

        state[1] = getBitFromByteArray(input, 56, 31) or getBitFromByteArray(
            input,
            48,
            30
        ) or getBitFromByteArray(
            input,
            40,
            29
        ) or getBitFromByteArray(input, 32, 28) or
                getBitFromByteArray(input, 24, 27) or getBitFromByteArray(
            input,
            16,
            26
        ) or getBitFromByteArray(
            input,
            8,
            25
        ) or getBitFromByteArray(input, 0, 24) or
                getBitFromByteArray(input, 58, 23) or getBitFromByteArray(
            input,
            50,
            22
        ) or getBitFromByteArray(
            input,
            42,
            21
        ) or getBitFromByteArray(input, 34, 20) or
                getBitFromByteArray(input, 26, 19) or getBitFromByteArray(
            input,
            18,
            18
        ) or getBitFromByteArray(
            input,
            10,
            17
        ) or getBitFromByteArray(input, 2, 16) or
                getBitFromByteArray(input, 60, 15) or getBitFromByteArray(
            input,
            52,
            14
        ) or getBitFromByteArray(
            input,
            44,
            13
        ) or getBitFromByteArray(input, 36, 12) or
                getBitFromByteArray(input, 28, 11) or getBitFromByteArray(
            input,
            20,
            10
        ) or getBitFromByteArray(
            input,
            12,
            9
        ) or getBitFromByteArray(input, 4, 8) or
                getBitFromByteArray(input, 62, 7) or getBitFromByteArray(
            input,
            54,
            6
        ) or getBitFromByteArray(
            input,
            46,
            5
        ) or getBitFromByteArray(input, 38, 4) or
                getBitFromByteArray(input, 30, 3) or getBitFromByteArray(
            input,
            22,
            2
        ) or getBitFromByteArray(
            input,
            14,
            1
        ) or getBitFromByteArray(input, 6, 0)
    }

    private fun inverseInitialPermutation(state: IntArray, output: ByteArray) {
        val outIndices = intArrayOf(3, 2, 1, 0, 7, 6, 5, 4)
        val bitOffsets = intArrayOf(7, 6, 5, 4, 3, 2, 1, 0)

        for (i in 0..7) {
            output[outIndices[i]] =
                (getBitFromIntR(state[1], bitOffsets[i], 7) or getBitFromIntR(
                    state[0],
                    bitOffsets[i],
                    6
                ) or
                        getBitFromIntR(state[1], bitOffsets[i] + 8, 5) or getBitFromIntR(
                    state[0],
                    bitOffsets[i] + 8,
                    4
                ) or
                        getBitFromIntR(state[1], bitOffsets[i] + 16, 3) or getBitFromIntR(
                    state[0],
                    bitOffsets[i] + 16,
                    2
                ) or
                        getBitFromIntR(state[1], bitOffsets[i] + 24, 1) or getBitFromIntR(
                    state[0],
                    bitOffsets[i] + 24,
                    0
                )
                        ).toByte()
        }
    }

    private fun f(stateIn: Int, key: ByteArray): Int {
        val t1 =
            getBitFromIntL(stateIn, 31, 0) or ((stateIn and -0x10000000) ushr 1) or getBitFromIntL(
                stateIn,
                4,
                5
            ) or getBitFromIntL(stateIn, 3, 6) or
                    ((stateIn and 0x0f000000) ushr 3) or getBitFromIntL(
                stateIn,
                8,
                11
            ) or getBitFromIntL(
                stateIn,
                7,
                12
            ) or ((stateIn and 0x00f00000) ushr 5) or
                    getBitFromIntL(stateIn, 12, 17) or getBitFromIntL(
                stateIn,
                11,
                18
            ) or ((stateIn and 0x000f0000) ushr 7) or getBitFromIntL(stateIn, 16, 23)

        val t2 =
            getBitFromIntL(stateIn, 15, 0) or ((stateIn and 0x0000f000) shl 15) or getBitFromIntL(
                stateIn,
                20,
                5
            ) or getBitFromIntL(stateIn, 19, 6) or
                    ((stateIn and 0x00000f00) shl 13) or getBitFromIntL(
                stateIn,
                24,
                11
            ) or getBitFromIntL(
                stateIn,
                23,
                12
            ) or ((stateIn and 0x000000f0) shl 11) or
                    getBitFromIntL(stateIn, 28, 17) or getBitFromIntL(
                stateIn,
                27,
                18
            ) or ((stateIn and 0x0000000f) shl 9) or getBitFromIntL(stateIn, 0, 23)

        val x0 = ((t1 ushr 24) and 0xFF) xor (key[0].toInt() and 0xFF)
        val x1 = ((t1 ushr 16) and 0xFF) xor (key[1].toInt() and 0xFF)
        val x2 = ((t1 ushr 8) and 0xFF) xor (key[2].toInt() and 0xFF)
        val x3 = ((t2 ushr 24) and 0xFF) xor (key[3].toInt() and 0xFF)
        val x4 = ((t2 ushr 16) and 0xFF) xor (key[4].toInt() and 0xFF)
        val x5 = ((t2 ushr 8) and 0xFF) xor (key[5].toInt() and 0xFF)

        val state =
            (box1[formatSBoxInput(x0 ushr 2)] shl 28) or (box2[formatSBoxInput(((x0 and 0x03) shl 4) or (x1 ushr 4))] shl 24) or
                    (box3[formatSBoxInput(((x1 and 0x0f) shl 2) or (x2 ushr 6))] shl 20) or (box4[formatSBoxInput(
                x2 and 0x3f
            )] shl 16) or
                    (box5[formatSBoxInput(x3 ushr 2)] shl 12) or (box6[formatSBoxInput(((x3 and 0x03) shl 4) or (x4 ushr 4))] shl 8) or
                    (box7[formatSBoxInput(((x4 and 0x0f) shl 2) or (x5 ushr 6))] shl 4) or box8[formatSBoxInput(
                x5 and 0x3f
            )]

        return getBitFromIntL(state, 15, 0) or getBitFromIntL(state, 6, 1) or getBitFromIntL(
            state,
            19,
            2
        ) or getBitFromIntL(state, 20, 3) or
                getBitFromIntL(state, 28, 4) or getBitFromIntL(state, 11, 5) or getBitFromIntL(
            state,
            27,
            6
        ) or getBitFromIntL(state, 16, 7) or
                getBitFromIntL(state, 0, 8) or getBitFromIntL(state, 14, 9) or getBitFromIntL(
            state,
            22,
            10
        ) or getBitFromIntL(state, 25, 11) or
                getBitFromIntL(state, 4, 12) or getBitFromIntL(state, 17, 13) or getBitFromIntL(
            state,
            30,
            14
        ) or getBitFromIntL(state, 9, 15) or
                getBitFromIntL(state, 1, 16) or getBitFromIntL(state, 7, 17) or getBitFromIntL(
            state,
            23,
            18
        ) or getBitFromIntL(state, 13, 19) or
                getBitFromIntL(state, 31, 20) or getBitFromIntL(state, 26, 21) or getBitFromIntL(
            state,
            2,
            22
        ) or getBitFromIntL(state, 8, 23) or
                getBitFromIntL(state, 18, 24) or getBitFromIntL(state, 12, 25) or getBitFromIntL(
            state,
            29,
            26
        ) or getBitFromIntL(state, 5, 27) or
                getBitFromIntL(state, 21, 28) or getBitFromIntL(state, 10, 29) or getBitFromIntL(
            state,
            3,
            30
        ) or getBitFromIntL(state, 24, 31)
    }

    private fun crypt(input: ByteArray, output: ByteArray, key: Array<ByteArray>) {
        val state = IntArray(2)
        initialPermutation(state, input)
        for (idx in 0..<15) {
            val t = state[1]
            state[1] = f(state[1], key[idx]) xor state[0]
            state[0] = t
        }
        state[0] = f(state[1], key[15]) xor state[0]
        inverseInitialPermutation(state, output)
    }
}