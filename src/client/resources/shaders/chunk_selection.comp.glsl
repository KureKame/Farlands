#version 450

layout(local_size_x = 64) in;

layout(binding = 0) uniform FrameUniforms {
    mat4 invViewProj;
    mat4 viewProj;
    vec4 cameraPos;
    ivec4 chunkCountPacked;
    vec4 frustumPlanes[6];
} frame;

struct ChunkEntry {
    uint rootIndex;
    float originX, originY, originZ;
    float size;
};

layout(std430, binding = 1) readonly buffer iChunkList {
    ChunkEntry iChunks[];
};

layout(std430, binding = 2) writeonly buffer oChunkList {
    ChunkEntry oChunks[];
};

layout(std430, set = 0, binding = 3) buffer VisibleChunkCount {
    uint visibleCount;
};

bool chunkInFrustrum(vec3 minPos, vec3 maxPos) {
    vec3 relMin = minPos - frame.cameraPos.xyz;
    vec3 relMax = maxPos - frame.cameraPos.xyz;

    for (int i = 0; i < 6; ++i) {
        vec3 normal = frame.frustumPlanes[i].xyz;
        float dist = frame.frustumPlanes[i].w;

        vec3 p = vec3(
            normal.x >= 0.0 ? relMax.x : relMin.x,
            normal.y >= 0.0 ? relMax.y : relMin.y,
            normal.z >= 0.0 ? relMax.z : relMin.z
        );

        if (dot(normal, p) + dist < 0.0) {
            return false;
        }
    }
    return true;
}

void main() {
    uint chunkIndex = gl_GlobalInvocationID.x;
    uint totalChunks = uint(frame.chunkCountPacked.x);

    if (chunkIndex >= totalChunks) {
        return;
    }

    ChunkEntry chunk = iChunks[chunkIndex];

    vec3 minPos = vec3(chunk.originX, chunk.originY, chunk.originZ);
    vec3 maxPos = minPos + vec3(chunk.size);

    if (chunkInFrustrum(minPos, maxPos)) {
        uint appendIndex = atomicAdd(visibleCount, 1u);

        oChunks[appendIndex] = chunk;
    }
}