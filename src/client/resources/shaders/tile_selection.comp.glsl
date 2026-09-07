#version 450

layout(local_size_x = 4, local_size_y = 4) in;

struct ChunkEntry {
    uint rootIndex;
    float originX, originY, originZ;
    float size;
};

const uint MAX_CHUNKS_PER_TILE = 512u;

layout(binding = 0) uniform sampler2D inputDepth;

layout(binding = 1) uniform FrameUniforms {
    mat4 invViewProj;
    mat4 viewProj;
    vec4 cameraPos;
    ivec4 chunkCountPacked;
    vec4 frustrumPlanes[6];
} frame;

layout(std430, binding = 2) readonly buffer ChunkList { ChunkEntry chunks[]; };
layout(std430, binding = 3) readonly buffer VisibleChunkCount { uint visibleChunkCount; };

layout(std430, binding = 4) writeonly buffer TileChunkIndices { uint tileChunkIndices[]; };
layout(std430, binding = 5) writeonly buffer TileChunkCounts  { uint tileChunkCount[]; };
layout(std430, binding = 6) writeonly buffer TileDepths       { float tileConservativeDepth[]; };

bool rayIntersectsAabb(vec3 rayOrigin, vec3 rayDir, vec3 chunkMin, vec3 chunkMax, out float tEntry, out float tExit) {
    vec3 invDir = 1.0 / rayDir;
    vec3 t0 = (chunkMin - rayOrigin) * invDir;
    vec3 t1 = (chunkMax - rayOrigin) * invDir;

    vec3 tMin = min(t0, t1);
    vec3 tMax = max(t0, t1);

    tEntry = max(tMin.x, max(tMin.y, tMin.z));
    tExit  = min(tMax.x, min(tMax.y, tMax.z));

    if (tExit < 0.0) {
        return false;
    }

    return tExit >= max(0.0, tEntry);
}

vec3 computeRayDirectionForPixel(vec2 pixelCoord, vec2 screenSize, mat4 invViewProj) {
    vec2 ndc = (pixelCoord / screenSize) * 2.0 - 1.0;
    ndc.y = -ndc.y;

    vec4 nearTarget = invViewProj * vec4(ndc, 1.0, 1.0);
    vec4 farTarget  = invViewProj * vec4(ndc, 0.0, 1.0);

    nearTarget /= nearTarget.w;
    farTarget  /= farTarget.w;

    return normalize(farTarget.xyz - nearTarget.xyz);
}

void main() {
    ivec2 tileCoord = ivec2(gl_GlobalInvocationID.xy);

    int screenWidth = frame.chunkCountPacked.y;
    int screenHeight = frame.chunkCountPacked.z;
    ivec2 tileGridSize = ivec2((screenWidth + 7) / 8, (screenHeight + 7) / 8);

    if (tileCoord.x >= tileGridSize.x || tileCoord.y >= tileGridSize.y) {
        return;
    }

    uint tileIndex = uint(tileCoord.y * tileGridSize.x + tileCoord.x);
    vec2 screenSize = vec2(float(screenWidth), float(screenHeight));

    vec2 pixelMin = vec2(tileCoord) * 8.0;
    vec2 pixelMax = pixelMin + vec2(8.0);
    bool skyPresent = false;

    for (int x = 0; x < 8; x++) {
        for (int y = 0; y < 8; y++) {
            vec2 samplePixel = pixelMin + vec2(x, y);
            if (samplePixel.x < screenWidth && samplePixel.y < screenHeight) {
                vec2 uv = (vec2(samplePixel) + 0.5) / vec2(screenSize);
                float depth = texture(inputDepth, uv).r;
                if (depth == 0.0) {
                    skyPresent = true;
                    break;
                }
            }
        }
        if (skyPresent) break;
    }

    if (!skyPresent) {
        tileChunkCount[tileIndex] = 0;
        return;
    }

    vec3 cornerRays[4];
    cornerRays[0] = computeRayDirectionForPixel(vec2(pixelMin.x, pixelMin.y), screenSize, frame.invViewProj);
    cornerRays[1] = computeRayDirectionForPixel(vec2(pixelMax.x, pixelMin.y), screenSize, frame.invViewProj);
    cornerRays[2] = computeRayDirectionForPixel(vec2(pixelMin.x, pixelMax.y), screenSize, frame.invViewProj);
    cornerRays[3] = computeRayDirectionForPixel(vec2(pixelMax.x, pixelMax.y), screenSize, frame.invViewProj);

    vec3 rayOrigin = frame.cameraPos.xyz;
    uint chunkCount = uint(visibleChunkCount);

    uint count = 0u;
    float conservativeDepth = 0.0;

    for (uint i = 0u; i < chunkCount; i++) {
        if (count >= MAX_CHUNKS_PER_TILE) {
            break;
        }

        ChunkEntry c = chunks[i];
        vec3 chunkMin = vec3(c.originX, c.originY, c.originZ);
        vec3 chunkMax = chunkMin + vec3(c.size);

        bool anyCornerHits = false;
        float nearestEntryThisChunk = 1e30;
        int nearestCornerIdx = -1;

        for (int corner = 0; corner < 4; corner++) {
            float tEntry, tExit;
            if (rayIntersectsAabb(rayOrigin, cornerRays[corner], chunkMin, chunkMax, tEntry, tExit)) {
                anyCornerHits = true;
                if (tEntry < nearestEntryThisChunk) {
                    nearestEntryThisChunk = tEntry;
                    nearestCornerIdx = corner;
                }
            }
        }

        if (anyCornerHits) {
            tileChunkIndices[tileIndex * MAX_CHUNKS_PER_TILE + count] = i;
            count++;

            vec3 worldPos = rayOrigin + cornerRays[nearestCornerIdx] * nearestEntryThisChunk;
            vec3 relativePos = worldPos - rayOrigin;
            vec4 clipPos = frame.viewProj * vec4(relativePos, 1.0);
            float chunkDepth = clipPos.z / clipPos.w;

            conservativeDepth = max(conservativeDepth, chunkDepth);
        }
    }

    tileChunkCount[tileIndex] = count;
    tileConservativeDepth[tileIndex] = conservativeDepth;
}