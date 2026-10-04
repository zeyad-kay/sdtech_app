package com.example.sdtech_android_native.asr

import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType

/**
 * Greedy RNNT/TDT decoding loop over a FastConformer encoder's hidden states,
 * ported from the litert-samples speech_recognition sample's TdtDecoder.
 *
 * Each encoder time step's joint-network output is a concatenation of vocab
 * logits followed by [AsrConfig.NUM_DURATIONS] duration logits. On a non-blank
 * token, the token is emitted and the LSTM predictor advances; on blank, only
 * time advances (by the predicted duration, or by 1 if duration is also 0).
 * The first emission per chunk uses the stateless "decode" signature (full
 * token history, better quality); subsequent steps switch to the stateful
 * "decode_1" signature, ping-ponging LSTM (hidden, cell) state buffers between
 * input/output slots across calls to avoid copying them.
 */
class TdtGreedyDecoder(private val model: CompiledModel) {

    private val inputBuffers = model.createInputBuffers(AsrConfig.DECODE_SIGNATURE)
    private val outputBuffers = model.createOutputBuffers(AsrConfig.DECODE_SIGNATURE)

    private val maxTimeIndex: Int
    private val numOfTokenIds: Int
    private val numOfLogitsPerToken: Int

    private val inputBuffersOfDecode1: List<TensorBuffer>?
    private val outputBuffersOfDecode1: List<TensorBuffer>?
    private val hasDecode1: Boolean

    private val statesBuffers: List<List<TensorBuffer>>
    private var inputStatesBuffersIndex = 0
    private val numOfStates: Int

    init {
        val arg0Type = model.getInputTensorType(inputBufferName(0), AsrConfig.DECODE_SIGNATURE)
        maxTimeIndex = numElements(arg0Type) / AsrConfig.ENCODER_HIDDEN_DIM

        val arg1Type = model.getInputTensorType(inputBufferName(1), AsrConfig.DECODE_SIGNATURE)
        numOfTokenIds = numElements(arg1Type)

        val out0Type = model.getOutputTensorType(outputBufferName(0), AsrConfig.DECODE_SIGNATURE)
        numOfLogitsPerToken = numElements(out0Type) / numOfTokenIds / maxTimeIndex

        inputBuffersOfDecode1 = try {
            model.createInputBuffers(AsrConfig.DECODE_1_SIGNATURE)
        } catch (e: Exception) {
            null
        }
        hasDecode1 = inputBuffersOfDecode1 != null
        outputBuffersOfDecode1 = if (hasDecode1) model.createOutputBuffers(AsrConfig.DECODE_1_SIGNATURE) else null

        statesBuffers = listOf(
            listOf(inputBuffers[2], inputBuffers[3]),
            listOf(outputBuffers[1], outputBuffers[2]),
        )
        val stateType = model.getInputTensorType(inputBufferName(2), AsrConfig.DECODE_SIGNATURE)
        numOfStates = numElements(stateType)
    }

    /** Runs the greedy decode loop over one chunk's encoder output buffers. Returns the emitted token ids, in order. */
    fun decode(encodeOutputBuffers: List<TensorBuffer>): List<Int> {
        val emitted = mutableListOf<Int>()

        val zeroStates = FloatArray(numOfStates) { 0f }
        statesBuffers[inputStatesBuffersIndex].forEach { it.writeFloat(zeroStates) }

        var inferenceSignature = AsrConfig.DECODE_SIGNATURE
        var numOfInferenceTokenIds = numOfTokenIds
        var inferenceTokenIdsBuffer = inputBuffers[1]
        var inferenceTokenIds = IntArray(numOfTokenIds) { 0 }
        inferenceTokenIds[0] = AsrConfig.BLANK_TOKEN_ID
        var inferenceLogitsBuffer = outputBuffers[0]

        var tokenIndex = 0
        var timeIndex = 0

        while (timeIndex < maxTimeIndex) {
            inferenceTokenIdsBuffer.writeInt(inferenceTokenIds)
            model.run(
                buildList {
                    addAll(encodeOutputBuffers)
                    add(inferenceTokenIdsBuffer)
                    addAll(statesBuffers[inputStatesBuffersIndex])
                },
                buildList {
                    add(inferenceLogitsBuffer)
                    addAll(statesBuffers[1 - inputStatesBuffersIndex])
                },
                inferenceSignature,
            )

            val logits = inferenceLogitsBuffer.readFloat()
            val startIndexInCurrentTimeIndex = timeIndex * numOfInferenceTokenIds * numOfLogitsPerToken
            val startIndexOfTokenId = startIndexInCurrentTimeIndex + tokenIndex * numOfLogitsPerToken
            val endIndexOfDuration = startIndexInCurrentTimeIndex + (tokenIndex + 1) * numOfLogitsPerToken
            val endIndexOfTokenId = endIndexOfDuration - AsrConfig.NUM_DURATIONS

            var bestTokenScore = Float.NEGATIVE_INFINITY
            var tokenId = 0
            for (i in startIndexOfTokenId until endIndexOfTokenId) {
                if (logits[i] > bestTokenScore) {
                    bestTokenScore = logits[i]
                    tokenId = i - startIndexOfTokenId
                }
            }

            if (tokenId != AsrConfig.BLANK_TOKEN_ID) {
                emitted.add(tokenId)
                if (numOfInferenceTokenIds > 1) {
                    tokenIndex++
                    if (tokenIndex < numOfInferenceTokenIds) {
                        // keep filling the stateless token array
                    } else if (hasDecode1) {
                        inferenceSignature = AsrConfig.DECODE_1_SIGNATURE
                        numOfInferenceTokenIds = 1
                        inferenceTokenIdsBuffer = inputBuffersOfDecode1!![1]
                        inferenceTokenIds = IntArray(1) { 0 }
                        inferenceLogitsBuffer = outputBuffersOfDecode1!![0]
                        tokenIndex = 0
                    } else {
                        break
                    }
                }
                inferenceTokenIds[tokenIndex] = tokenId
            }

            var bestDurationScore = Float.NEGATIVE_INFINITY
            var duration = 0
            for (i in endIndexOfTokenId until endIndexOfDuration) {
                if (logits[i] > bestDurationScore) {
                    bestDurationScore = logits[i]
                    duration = i - endIndexOfTokenId
                }
            }
            timeIndex += if (duration == 0 && tokenId == AsrConfig.BLANK_TOKEN_ID) 1 else duration

            if (numOfInferenceTokenIds == 1 && tokenId != AsrConfig.BLANK_TOKEN_ID) {
                inputStatesBuffersIndex = 1 - inputStatesBuffersIndex
            }
        }

        return emitted
    }

    private companion object {
        fun inputBufferName(index: Int) = "args_$index"
        fun outputBufferName(index: Int) = "output_$index"
        fun numElements(type: TensorType): Int =
            checkNotNull(type.layout) { "Tensor type has no layout" }.dimensions.fold(1) { acc, d -> acc * d }
    }
}
