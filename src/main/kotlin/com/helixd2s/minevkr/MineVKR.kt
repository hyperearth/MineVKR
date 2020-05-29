package com.helixd2s.minevkr

import com.google.common.collect.Maps
import com.helixd2s.jivix.JiviX
import com.helixd2s.minevkr.ducks.*
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.fabricmc.api.ModInitializer
import net.minecraft.client.gl.VertexBuffer
import net.minecraft.client.render.*
import net.minecraft.client.render.chunk.ChunkBuilder
import net.minecraft.client.util.Window
import net.minecraft.client.util.math.MatrixStack
import net.minecraft.util.Identifier
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.MathHelper
//import net.minecraft.util.math.MathHelper // BROKEN PACKAGE!
import net.minecraft.util.math.Matrix4f
import org.lwjgl.opengl.GL30.glBeginTransformFeedback
import org.lwjgl.opengl.GL30.glEndTransformFeedback
import org.lwjgl.opengl.GL32.*
import org.lwjgl.opengl.GL44.GL_CLIENT_STORAGE_BIT
import org.lwjgl.opengl.GL44.GL_DYNAMIC_STORAGE_BIT
import org.lwjgl.opengl.GL44.glBufferStorage
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import java.io.File

open class MineVKR : ModInitializer {

    object CurrentChunk {
        open var vVertexBuffer: VertexBuffer? = null
        open var vBlockPos: BlockPos? = null
        open var vCurrentChunk: ChunkBuilder.BuiltChunk? = null
        open var vVertexFormat: VertexFormat? = null
        open var vCPosition: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)
    }

    object Entity {
        open var vVertexConsumers: VertexConsumerProvider? = null
        open var vEntity: net.minecraft.entity.Entity? = null
        open var vMatrices: MatrixStack? = null
        open var vCameraXYZ: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0)
        open var vTickDelta: Float = 0F
    }

    object GLStuff { // For OpenGL needs pointable objects
        open var vTexVkMap: HashMap<Integer, JiviX.ImageRegion> = Maps.newHashMap<Integer, JiviX.ImageRegion>()
        open var vIdxMtMap: HashMap<Identifier, Int> = Maps.newHashMap<Identifier, Int>() // Second are ID from vMaterial

        open var vShowVertexShader: UIntArray = uintArrayOf(0u)
        open var vShowFragmentShader: UIntArray = uintArrayOf(0u)
        open var vShowProgram: UIntArray = uintArrayOf(0u)

        open var vTransformFeedbackVertexShader: UIntArray = uintArrayOf(0u)
        open var vTransformFeedbackGeometryShader: UIntArray = uintArrayOf(0u)
        open var vTransformFeedbackProgram: UIntArray = uintArrayOf(0u)

        open var vQuadTransformFeedbackVertexShader: UIntArray = uintArrayOf(0u)
        open var vQuadTransformFeedbackGeometryShader: UIntArray = uintArrayOf(0u)
        open var vQuadTransformFeedbackProgram: UIntArray = uintArrayOf(0u)

        open var vGLTestBuffer = intArrayOf(0);

        open fun vCreateShader(shader: UIntArray, type: Int, path: String): UIntArray {
            var success = intArrayOf(0)
            return shader
                .also { it[0] = glCreateShader(type).toUInt() }
                .also {
                    glShaderSource(it[0].toInt(), File(path).readText())
                    glCompileShader(it[0].toInt())
                    var success = intArrayOf(0).also { ms -> glGetShaderiv(it[0].toInt(), GL_COMPILE_STATUS, ms) }
                    if (success[0] == 0) {
                        println(glGetShaderInfoLog(it[0].toInt(), 512))
                        error("LOL")
                    }
                }
            // glGetShaderiv(vertexShader, GL_COMPILE_STATUS, &success);
        }
    }

    // TODO: Improved First Person support...
    object Player {
        open var vCamera: Camera? = null
        open var vMatrix4f: Matrix4f? = null
        open var vMatrixStack: MatrixStack? = null
    }

    companion object {
        open var vWindow: Window? = null
        open var vWorldRenderer: WorldRenderer? = null
        open var vGameRenderer: GameRenderer? = null
        open var vRenderer: JiviX.Renderer? = null
        open var vDriver: JiviX.Driver? = null
        open var vContext: JiviX.Context? = null
        open var vMaterials: JiviX.Material? = null
        open var vInstance: VkInstance? = null
        open var vPhysicalDevice: VkPhysicalDevice? = null
        open var vDevice: VkDevice? = null

        //
        //open var vWidth = 1600
        //open var vHeight = 1200

        //
        open var vPhysicalDeviceHandle = 0UL
        open var vDeviceHandle = 0UL
        open var vInstanceHandle = 0UL

        //
        open var vInitialized: Boolean = false
        open var vPreInitialized: Boolean = false

        //
        open var vBindingsEntity = arrayOf<JiviX.MeshBinding>()
        open var vBindingsChunksOpaque = arrayOf<JiviX.MeshBinding>()
        open var vBindingsChunksTranslucent = arrayOf<JiviX.MeshBinding>()
        open var vBindingsChunksCutout = arrayOf<JiviX.MeshBinding>()

        //
        open var vNode = arrayOf<JiviX.Node>()

        // TODO: Settings For Constants
        const val vMaxChunkBindings = 16
        const val vMaxEntityBindings = 16
        const val vMaxEntityParts = 16

        //
        open var vChunkCounter = 0

        // For Entity!
        open var vIndexCounter = 0
        open var vEntityCounter = 0



        // октюбинск...
        open fun uTextureInit(gLFormat: net.minecraft.client.texture.NativeImage.GLFormat, i: Int, j: Int, k: Int, l: Int) {
            RenderSystem.assertThread { RenderSystem.isOnRenderThreadOrInit() }
            GlStateManager.bindTexture(i)

            var glformat = (gLFormat as GLFormat).glConstant
            var vkformat = VK10.VK_FORMAT_R8G8B8A8_UNORM

            var imageCreateInfo = VkImageCreateInfo.create()
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(VK10.VK_IMAGE_TYPE_2D)
                .format(vkformat)
                .extent(VkExtent3D.calloc().also { it.width(k).height(l).depth(1) })
                .mipLevels(1) // TODO: Mip Levels Support
                .arrayLayers(1)
                .samples(VK10.VK_SAMPLE_COUNT_1_BIT)
                .tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT.or(VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT).or(VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT))
                .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)

            var imageViewCreateInfo = VkImageViewCreateInfo.create()
                .sType(VK10.VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .viewType(VK10.VK_IMAGE_VIEW_TYPE_2D)
                .format(vkformat)
                .components(VkComponentMapping.create().also{it.r(VK_COMPONENT_SWIZZLE_R).g(VK_COMPONENT_SWIZZLE_G).b(VK_COMPONENT_SWIZZLE_B).a(VK_COMPONENT_SWIZZLE_A)}) //VK_COMPONENT_SWIZZLE_R
                .subresourceRange(VkImageSubresourceRange.create().also{it.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1)})

            // Create With GL memory
            var imageAllocation = MineVKR.vDriver?.memoryAllocationInfo.also{
                if (it != null) { it.glID = i }
            }?.let { JiviX.ImageAllocation(imageCreateInfo, it) }
            var imageView = imageAllocation?.let { JiviX.ImageRegion(it, imageViewCreateInfo) }

            //
            println("Mapping OpenGL Texture[$i]...")
            if (imageView != null) { MineVKR.GLStuff.vTexVkMap.put(Integer(i), imageView) }
        }

        //
        open fun vRenderBegin(matrices: MatrixStack?, tickDelta: Float, limitTime: Long, renderBlockOutline: Boolean, camera: Camera?, gameRenderer: GameRenderer?, lightmapTextureManager: LightmapTextureManager?, matrix4f: Matrix4f?, ci: CallbackInfo) {
            if (gameRenderer != null) { MineVKR.vGameRenderer = gameRenderer }
            if (matrices != null) { MineVKR.Player.vMatrix4f = Matrix4f(matrices.peek().model) }
            if (camera != null) { MineVKR.Player.vCamera = camera }

            //
            for (element in vBindingsChunksOpaque) element.resetGeometry()
            for (element in vBindingsChunksCutout) element.resetGeometry()
            for (element in vBindingsChunksTranslucent) element.resetGeometry()

            //
            MineVKR.vMaterials?.resetMaterials()
            MineVKR.vNode[0].resetInstances()
            MineVKR.vChunkCounter = 0
            MineVKR.vIndexCounter = 0

            // Test Material
            var material = JiviX.MaterialUnit();
            material.diffuse = floatArrayOf(1.0F, 1.0F, 1.0F, 1.0F)
            material.emission = floatArrayOf(0.0F, 0.0F, 0.0F, 1.0F)
            material.normals = floatArrayOf(0.0F, 0.0F, 1.0F, 1.0F)
            MineVKR.vMaterials?.pushMaterial(material)

            // TODO: Fix Hand Rendering
            var perspective = matrix4f//MineVKR.vGameRenderer.getBasicProjectionMatrix(camera, tickDelta, false)
            MineVKR.vContext?.setPerspective((perspective.also{ it?.transpose() } as IEMatrix4f).toArray())

            //
            if (matrices != null) {
                matrices.push()
                MineVKR.vContext?.setModelView((matrices.peek().model as IEMatrix4f).toArray())
                matrices.pop()
            }
        }

        //
        open fun vRenderEnd(matrices: MatrixStack?, tickDelta: Float, limitTime: Long, renderBlockOutline: Boolean, camera: Camera?, gameRenderer: GameRenderer?, lightmapTextureManager: LightmapTextureManager?, matrix4f: Matrix4f?, ci: CallbackInfo) {
            var fullCommand = MineVKR.vRenderer?.setupCommands()?.refCommandBuffer()

        }

        //
        open fun vRenderLayerBegin(renderLayer: RenderLayer, matrixStack: MatrixStack, d: Double, e: Double, f: Double, ci: CallbackInfo) {
            MineVKR.Player.vMatrixStack = matrixStack
            MineVKR.vChunkCounter = 0
            MineVKR.vIndexCounter = 0
        }

        //
        open fun vRenderLayerEnd(renderLayer: RenderLayer, matrixStack: MatrixStack, d: Double, e: Double, f: Double, ci: CallbackInfo) {

        }

        // Used For Entity! USED WITH `THIS`
        open fun onRenderLayerDraw(renderLayer: RenderLayer, buffer: BufferBuilder, cameraX: Int, cameraY: Int, cameraZ: Int, ci: CallbackInfo) {
            // getting texture identifier for access from vulkan textures
            var phases = (renderLayer as IERenderLayer)?.phases?:null
            var texture = (phases as IEMultiPhase)?.texture?:null
            var indentifier = (texture as IETexture)?.id?:null

            //
            var tickDelta = MineVKR.Entity.vTickDelta
            var entity = MineVKR.Entity.vEntity
            var cameraPos = MineVKR.Entity.vCameraXYZ
            var entityPos = doubleArrayOf(0.0,0.0,0.0); var g = 0.0F
            if (entity != null) {
                entityPos[0] = MathHelper.lerp(tickDelta.toDouble(), entity.lastRenderX, entity.x)
                entityPos[1] = MathHelper.lerp(tickDelta.toDouble(), entity.lastRenderY, entity.y)
                entityPos[2] = MathHelper.lerp(tickDelta.toDouble(), entity.lastRenderZ, entity.z)
                g = MathHelper.lerp(tickDelta, entity.prevYaw, entity.yaw)
            }

            // Get Transformation Without Camera Transform
            if (MineVKR.Player.vMatrix4f != null) {
                var matrixStack = MineVKR.Entity.vMatrices
                var transformed = Matrix4f(MineVKR.Player.vMatrix4f).also{it.invert()}.also{
                    if (matrixStack != null) { it.multiply(matrixStack.peek().model) } }
            }

            // Use Host Pointer
            glBindBuffer(GL_ARRAY_BUFFER, 0)
            glBindVertexArray(0)

            //
            var vertexFormat = renderLayer.vertexFormat
            var ptr = MemoryUtil.memAddress((buffer as IEBufferBuilder).buffer)
            for (id in 0 until vertexFormat.elements.size) {
                var element = vertexFormat.elements[id]
                var offset = (vertexFormat as IEFormat).offsets.getInt(id)
                if (element.type.name == "Position"     ) { glVertexAttribPointer(0, (element as IEFormatElement).count, element.format.glId, false, vertexFormat.vertexSize, offset.toLong()+ptr) }
                if (element.type.name == "UV"           ) { glVertexAttribPointer(1, (element as IEFormatElement).count, element.format.glId, false, vertexFormat.vertexSize, offset.toLong()+ptr) }
                if (element.type.name == "Normal"       ) { glVertexAttribPointer(2, (element as IEFormatElement).count, element.format.glId, false, vertexFormat.vertexSize, offset.toLong()+ptr) }
                if (element.type.name == "Vertex Color" ) { glVertexAttribPointer(3, (element as IEFormatElement).count, element.format.glId, false, vertexFormat.vertexSize, offset.toLong()+ptr) }
            }

            // Make Sure That Object Is Real
            if (entity != null && indentifier != null && phases != null && MineVKR.vEntityCounter < MineVKR.vMaxEntityBindings) {
                val vertexBuffer = MineVKR.CurrentChunk.vCurrentChunk?.getBuffer(renderLayer) ?: null
                val chunkIndex = MineVKR.vEntityCounter++;
                val indexOffset = vIndexCounter
                var binding = vBindingsEntity[chunkIndex]

                // TODO: Rendering entity, unified method...

            }

        }

        //
        open fun vChunkDraw(renderLayer: RenderLayer, matrixStack: MatrixStack, d: Double, e: Double, f: Double, ci: CallbackInfo) {
            vIndexCounter = 0

            // getting texture identifier for access from vulkan textures
            var phases = (renderLayer as IERenderLayer)?.phases?:null
            var texture = (phases as IEMultiPhase)?.texture?:null
            var indentifier = (texture as IETexture)?.id?:null

            // Long Pinus
            val vertexBuffer = MineVKR.CurrentChunk.vCurrentChunk?.getBuffer(renderLayer) ?: null
            var vertexFormat = MineVKR.CurrentChunk.vVertexFormat
            if (indentifier != null && phases != null && MineVKR.vChunkCounter < vMaxChunkBindings) {
                val chunkIndex = MineVKR.vChunkCounter++;
                val indexOffset = vIndexCounter //; vIndexCounter += (vertexBuffer as IEVBuffer).vertexCount();
                var binding = vBindingsChunksOpaque[chunkIndex]

                //
                vertexBuffer?.bind()
                if (vertexFormat != null) {
                    for (id in 0 until vertexFormat.elements.size) {
                        var element = vertexFormat.elements[id]
                        var offset = (vertexFormat as IEFormat).offsets.getInt(id)
                        if (element.type.name == "Position"     ) { glVertexAttribPointer(0, (element as IEFormatElement).count, element.format.glId, false, vertexFormat.vertexSize, offset.toLong()) }
                        if (element.type.name == "UV"           ) { glVertexAttribPointer(1, (element as IEFormatElement).count, element.format.glId, false, vertexFormat.vertexSize, offset.toLong()) }
                        if (element.type.name == "Normal"       ) { glVertexAttribPointer(2, (element as IEFormatElement).count, element.format.glId, false, vertexFormat.vertexSize, offset.toLong()) }
                        if (element.type.name == "Vertex Color" ) { glVertexAttribPointer(3, (element as IEFormatElement).count, element.format.glId, false, vertexFormat.vertexSize, offset.toLong()) }
                    }
                }

                // minecraft used texcoord transform
                var texMatrix = floatArrayOf(1F, 0F, 0F, 0F, 0F, 1F, 0F, 0F, 0F, 0F, 1F, 0F, 0F, 0F, 0F, 1F)
                glGetFloatv(GL_TEXTURE_MATRIX, texMatrix)

                // TODO: Correct Working `I` of `vBindingsChunksOpaque[I]`
                //println("What is: GL-Buffers[" + vBindingsChunksOpaque[0].bindingBufferGL().toInt() + "] ?") // Only For DEBUG!
                glUseProgram(GLStuff.vQuadTransformFeedbackProgram[0].toInt())
                glBindBufferRange(GL_TRANSFORM_FEEDBACK_BUFFER, 0, binding.bindingBufferGL().toInt(), indexOffset * 80L, 80L * (vertexBuffer as IEVBuffer).vertexCount * 6 / 4)

                // Get Transformation Without Camera Transform
                var transformed = Matrix4f(MineVKR.Player.vMatrix4f).also{it.invert()}.also{it.multiply(matrixStack.peek().model)}

                // transform feedback required for form Vulkan API generalized buffer (i.e. blizzard tracing)
                // NO! You can'T to make parallax occlusion mapping here...
                glBeginTransformFeedback(GL_TRIANGLES)
                glEnable(GL_RASTERIZER_DISCARD)
                matrixStack.push()
                glUniformMatrix4fv(0, false, (transformed as IEMatrix4f).toArray())
                glUniformMatrix3fv(2, false, (matrixStack.peek().normal as IEMatrix3f).toArray())
                glUniformMatrix4fv(1, true, texMatrix) // MOST Important!
                matrixStack.pop()
                vertexBuffer.draw(matrixStack.peek().model, GL_LINES_ADJACENCY)
                glDisable(GL_RASTERIZER_DISCARD)
                glEndTransformFeedback()
                glUseProgram(0)

                //
                binding.addRangeInput((MineVKR.CurrentChunk.vVertexFormat?.vertexSize?.toULong() ?: 0UL) / 2U, 0u)

                //
                var instanceInfo = JiviX.VsGeometryInstance()
                instanceInfo.mask = 0xFFU
                instanceInfo.instanceId = chunkIndex.toUInt() // TODO: Translucent Blocks
                instanceInfo.instanceOffset = 0U
                instanceInfo.flags = 0x00000004U

                // required transposed matrix
                val blockPos = MineVKR.CurrentChunk.vCurrentChunk?.origin ?: null
                var matrixStack = MatrixStack().also { it.push() }

                // 
                if (blockPos != null) { matrixStack.translate(blockPos.x.toDouble() - d, blockPos.y.toDouble() - e, blockPos.z.toDouble() - f) }

                //
                MineVKR.vNode[0].pushInstance(instanceInfo.also {
                    var instanceMatrix = Matrix4f(transformed).also { mt -> mt.multiply(matrixStack.peek().model) }

                    it.transform = (instanceMatrix as IEMatrix4f).toArray()
                })

                //
                matrixStack.pop()
            }
        }

        open fun vInitializeDriver() { //
            if (!vPreInitialized) {
                vPreInitialized = true

                //
                MineVKR.vInstanceHandle = MineVKR.vDriver?.createInstance() ?: 0U
                MineVKR.vInstance = MineVKR.vDriver?.instanceClass!!

                // TODO: Support Other GPU's
                MineVKR.vPhysicalDevice = MineVKR.vDriver!!.physicalDeviceClass!!
                MineVKR.vPhysicalDeviceHandle = MineVKR.vDriver!!.physicalDevice

                //
                MineVKR.vDevice = MineVKR.vDriver!!.createDevice(MineVKR.vPhysicalDevice!!)
                MineVKR.vDeviceHandle = MineVKR.vDriver!!.device

                //
                println("Initialize Context...")

                //
                MineVKR.vContext = JiviX.Context(MineVKR.vDriver!!)
                MineVKR.vMaterials = JiviX.Material(MineVKR.vContext!!)
                MineVKR.vNode = arrayOf(JiviX.Node(MineVKR.vContext!!)) // TODO: Node Max Instance Count Support
                MineVKR.vRenderer = JiviX.Renderer(MineVKR.vContext!!)
                println("Pre-Initialize Renderer")

                //
                MineVKR.vRenderer!!.linkMaterial(MineVKR.vMaterials!!)
                MineVKR.vRenderer!!.linkNode(MineVKR.vNode[0])
                println("Link Node and Materials...")
            }
        }

        @JvmStatic
        open fun vInitializeRenderer() {
            if (!vInitialized) {
                vInitialized = true

                //
                MineVKR.vContext?.initialize(MineVKR.vWindow?.width?.toUInt()?:0U, MineVKR.vWindow?.height?.toUInt()?:0U)
                println("Initialize Context...")

                //
                MineVKR.vBindingsChunksOpaque = Array<JiviX.MeshBinding>(vMaxChunkBindings) { _ -> MineVKR.vContext?.let { JiviX.MeshBinding(it, 2048UL) }!!; }
                MineVKR.vBindingsChunksCutout = Array<JiviX.MeshBinding>(vMaxChunkBindings) { _ -> MineVKR.vContext?.let { JiviX.MeshBinding(it, 2048UL) }!!; }
                MineVKR.vBindingsChunksTranslucent = Array<JiviX.MeshBinding>(vMaxChunkBindings) { _ -> MineVKR.vContext?.let { JiviX.MeshBinding(it, 2048UL) }!!; }
                println("Create chunk bindings itself...")

                //
                for (element in vBindingsChunksOpaque) MineVKR.vNode[0].pushMesh(element)
                for (element in vBindingsChunksCutout) MineVKR.vNode[0].pushMesh(element)
                for (element in vBindingsChunksTranslucent) MineVKR.vNode[0].pushMesh(element)
                println("Add chunk bindings into Node...")

                //
                var vPartsSize = ULongArray(vMaxEntityParts) { _ -> 512UL }
                MineVKR.vBindingsEntity = Array<JiviX.MeshBinding>(vMaxEntityBindings) { _ -> MineVKR.vContext?.let { JiviX.MeshBinding(it, 512UL, vPartsSize) }!! }
                println("Create entity bindings itself...")

                //
                for (element in vBindingsEntity) MineVKR.vNode[0].pushMesh(element)
                println("Add entity bindings into Node...")

                //
                var vTransformFeedbackProgramBuild = true
                var vQuadTransformFeedbackProgramBuild = true
                var vShowProgramBuild = true

                //
                if (vTransformFeedbackProgramBuild) {
                    GLStuff.vCreateShader(GLStuff.vTransformFeedbackVertexShader, GL_VERTEX_SHADER, "./gl-shaders/tf.vert")
                    GLStuff.vCreateShader(GLStuff.vTransformFeedbackGeometryShader, GL_GEOMETRY_SHADER, "./gl-shaders/tf.geom")
                    println("GL Transform Feedback Shaders Created!")

                    //
                    if (true) {
                        var varyings = arrayOf<CharSequence>("fPosition", "fTexcoord", "fNormal", "fTangent", "fBinormal");
                        var programID = GLStuff.vTransformFeedbackProgram.also { it[0] = glCreateProgram().toUInt() }[0].toInt()
                        glTransformFeedbackVaryings(programID, varyings, GL_INTERLEAVED_ATTRIBS)
                        glAttachShader(programID, GLStuff.vTransformFeedbackVertexShader[0].toInt())
                        glAttachShader(programID, GLStuff.vTransformFeedbackGeometryShader[0].toInt())
                        glLinkProgram(programID)
                        var success = intArrayOf(0).also { glGetProgramiv(programID, GL_LINK_STATUS, it) }
                        if (success[0] == 0) { println(glGetProgramInfoLog(programID, 512)); error("LOL") }
                        println("GL Transform Feedback Program Created!")
                    }
                }

                //
                if (vQuadTransformFeedbackProgramBuild) {
                    GLStuff.vCreateShader(GLStuff.vQuadTransformFeedbackVertexShader, GL_VERTEX_SHADER, "./gl-shaders/tf-quad.vert")
                    GLStuff.vCreateShader(GLStuff.vQuadTransformFeedbackGeometryShader, GL_GEOMETRY_SHADER, "./gl-shaders/tf-quad.geom")
                    println("GL Quad Transform Feedback Shaders Created!")

                    //
                    if (true) {
                        var varyings = arrayOf<CharSequence>("fPosition", "fTexcoord", "fNormal", "fTangent", "fBinormal");
                        var programID = GLStuff.vQuadTransformFeedbackProgram.also { it[0] = glCreateProgram().toUInt() }[0].toInt()
                        glTransformFeedbackVaryings(programID, varyings, GL_INTERLEAVED_ATTRIBS)
                        glAttachShader(programID, GLStuff.vQuadTransformFeedbackVertexShader[0].toInt())
                        glAttachShader(programID, GLStuff.vQuadTransformFeedbackGeometryShader[0].toInt())
                        glLinkProgram(programID)
                        var success = intArrayOf(0).also { glGetProgramiv(programID, GL_LINK_STATUS, it) }
                        if (success[0] == 0) { println(glGetProgramInfoLog(programID, 512)); error("LOL") }
                        println("GL Quad Transform Feedback Programs Created!")
                    }
                }

                //
                if (vShowProgramBuild) {
                    GLStuff.vCreateShader(GLStuff.vShowVertexShader, GL_VERTEX_SHADER, "./gl-shaders/render.vert")
                    GLStuff.vCreateShader(GLStuff.vShowFragmentShader, GL_FRAGMENT_SHADER, "./gl-shaders/render.frag")
                    println("Show Result Shaders Created!")

                    //
                    if (true) {
                        var programID = GLStuff.vShowProgram.also { it[0] = glCreateProgram().toUInt() }[0].toInt()
                        glAttachShader(programID, GLStuff.vShowVertexShader[0].toInt())
                        glAttachShader(programID, GLStuff.vShowFragmentShader[0].toInt())
                        glLinkProgram(programID)
                        var success = intArrayOf(0).also { glGetProgramiv(programID, GL_LINK_STATUS, it) }
                        if (success[0] == 0) { println(glGetProgramInfoLog(programID, 512)); error("LOL") }
                        println("Show Result Program Created!")
                    }
                }

                // For Test ONLY!
                glGenBuffers(GLStuff.vGLTestBuffer)
                glBindBuffer(GL_TRANSFORM_FEEDBACK_BUFFER, GLStuff.vGLTestBuffer[0])
                glBufferStorage(GL_TRANSFORM_FEEDBACK_BUFFER, 80 * 128 * 6, GL_CLIENT_STORAGE_BIT.or(GL_DYNAMIC_STORAGE_BIT))

                // For Save... JavaCPP Pointer For Kotlin (By Address)
                // Also, ULong still needs to convert to Long, so needs bit-lossless conversion
                //val p: Pointer = object : Pointer() { init { address = 0xDEADBEEFL } }
            }
        }
    }

    @Override
    override fun onInitialize() {
        MineVKR.vDriver = JiviX.Driver()
    }

}
