#version 450

layout(local_size_x = 8, local_size_y = 8) in;

struct ChunkEntry {
    uint rootIndex;
    float originX, originY, originZ;
    float size;
};

struct HitResult {
    bool hit;
    float t;
    uint blockId;
    vec3 normal;
 };

struct StackEntry {
    uint nodeIndex;
    vec3 nodeMin;
    float nodeSize;
    uint mask;
    uint firstChildOffset;
    uint nextOrderIdx;
};

const uint MAX_CHUNKS_PER_TILE = 512u;

layout(binding = 0, rgba8) uniform image2D outputColor;
layout(binding = 1) uniform sampler2D inputDepth;

layout(binding = 2) uniform FrameUniforms {
    mat4 invViewProj;
    mat4 viewProj;
    vec4 cameraPos;
    ivec4 chunkCountPacked;
    vec4 frustrumPlanes[6];
} frame;

layout(std430, binding = 3) readonly buffer BlockPalette   { vec4 colors[]; };
layout(std430, binding = 4) readonly buffer ChunkList { ChunkEntry chunks[]; };

layout(std430, binding = 5) readonly buffer NodeHeaders    { uint headers[]; };
layout(std430, binding = 6) readonly buffer NodeSeconds    { uint seconds[]; };
layout(std430, binding = 7) readonly buffer ChildIndexPool { uint childPool[]; };

layout(std430, binding = 8) readonly buffer TileChunkIndices { uint tileChunkIndices[]; };
layout(std430, binding = 9) readonly buffer TileChunkCounts  { uint tileChunkCount[]; };
layout(std430, binding = 10) readonly buffer TileDepths      { float tileConservativeDepth[]; };

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

vec3 computeHitNormal(vec3 hitPoint, vec3 chunkMin, float chunkSize) {
    vec3 chunkCenter = chunkMin + vec3(chunkSize * 0.5);
    vec3 localHit = (hitPoint - chunkCenter) / (chunkSize * 0.5);
    vec3 absHit = abs(localHit);

    if (absHit.x > absHit.y && absHit.x > absHit.z) {
        return vec3(sign(localHit.x), 0.0, 0.0);
    } else if (absHit.y > absHit.z) {
        return vec3(0.0, sign(localHit.y), 0.0);
    } else {
        return vec3(0.0, 0.0, sign(localHit.z));
    }
}

vec3 computeRayDirection(ivec2 pixel, mat4 invViewProj) {
    ivec2 imageSize = imageSize(outputColor);

    vec2 ndc = (vec2(pixel) + vec2(0.5)) / vec2(imageSize) * 2.0 - 1.0;
    ndc.y = -ndc.y;

    vec4 nearTarget = invViewProj * vec4(ndc, 1.0, 1.0);
    vec4 farTarget  = invViewProj * vec4(ndc, 0.0, 1.0);

    nearTarget /= nearTarget.w;
    farTarget  /= farTarget.w;

    return normalize(farTarget.xyz - nearTarget.xyz);
}

uint childRank(uint mask, uint octant) {
    return bitCount(mask & ((1u << octant) - 1u));
}

HitResult traverseDag(uint rootIndex, vec3 rayOrigin, vec3 rayDir, vec3 boundsMin, float boundsSize, float maxT) {
    HitResult result;
    result.hit = false;

    if (rootIndex == 0xFFFFFFFFu) {
        return result;
    }

    uint firstBit = (rayDir.x < 0.0 ? 1u : 0u) | (rayDir.y < 0.0 ? 2u : 0u) | (rayDir.z < 0.0 ? 4u : 0u);

    const int MAX_STACK = 10;
    StackEntry stack[MAX_STACK];
    int stackPtr = 0;

    uint curNode = rootIndex;
    vec3 curMin = boundsMin;
    float curSize = boundsSize;
    uint curOrderIdx = 0u;
    bool haveCurrent = true;

    uint curMask = 0u;
    uint curFirstChildOffset = 0u;
    bool curIsInternalResolved = false;

    while (haveCurrent) {
        if (!curIsInternalResolved) {
            float tEntry, tExit;
            bool intersects = rayIntersectsAabb(rayOrigin, rayDir, curMin, curMin + vec3(curSize), tEntry, tExit);

            if (!intersects || tEntry > maxT) {
                haveCurrent = false;
            } else {
                uint mask = headers[curNode];
                if (mask == 0u) {
                    uint blockId = seconds[curNode];
                    if (!result.hit || tEntry < result.t) {
                        result.hit = true;
                        result.t = tEntry;
                        result.blockId = blockId;
                        result.normal = computeHitNormal(rayOrigin + rayDir * tEntry, curMin, curSize);
                    }
                    haveCurrent = false;
                } else {
                    curMask = mask;
                    curFirstChildOffset = seconds[curNode];
                    curIsInternalResolved = true;
                }
            }
        }

        if (haveCurrent && curIsInternalResolved) {
            bool foundChild = false;
            uint childOctant = 0u;

            while (curOrderIdx < 8u) {
                uint octant = curOrderIdx ^ firstBit;
                curOrderIdx++;
                if ((curMask & (1u << octant)) != 0u) {
                    childOctant = octant;
                    foundChild = true;
                    break;
                }
            }

            if (foundChild) {
                if (stackPtr < MAX_STACK) {
                    stack[stackPtr].nodeIndex = curNode;
                    stack[stackPtr].nodeMin = curMin;
                    stack[stackPtr].nodeSize = curSize;
                    stack[stackPtr].mask = curMask;
                    stack[stackPtr].firstChildOffset = curFirstChildOffset;
                    stack[stackPtr].nextOrderIdx = curOrderIdx;
                    stackPtr++;
                }

                uint rank = childRank(curMask, childOctant);
                uint childIndex = childPool[curFirstChildOffset + rank];

                float half_ = curSize * 0.5;
                float ox = float(childOctant & 1u);
                float oy = float((childOctant >> 1u) & 1u);
                float oz = float((childOctant >> 2u) & 1u);

                curNode = childIndex;
                curMin = curMin + vec3(ox, oy, oz) * half_;
                curSize = half_;
                curOrderIdx = 0u;
                curIsInternalResolved = false;
            } else {
                haveCurrent = false;
            }
        }

        if (!haveCurrent) {
            if (stackPtr > 0) {
                stackPtr--;
                curNode = stack[stackPtr].nodeIndex;
                curMin = stack[stackPtr].nodeMin;
                curSize = stack[stackPtr].nodeSize;
                curMask = stack[stackPtr].mask;
                curFirstChildOffset = stack[stackPtr].firstChildOffset;
                curOrderIdx = stack[stackPtr].nextOrderIdx;
                curIsInternalResolved = true;
                haveCurrent = true;
            }
        }
    }

    return result;
}

void main() {
    ivec2 pixel = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(outputColor);

    if (pixel.x >= size.x || pixel.y >= size.y) {
        return;
    }

    vec2 uv = (vec2(pixel) + 0.5) / vec2(size);
    float vanillaDepth = texture(inputDepth, uv).r;

    if (vanillaDepth > 0) {
        return;
    }

    ivec2 tileCoord = pixel / 8;
    ivec2 tileGridSize = ivec2((size.x + 7) / 8, (size.y + 7) / 8);
    uint tileIndex = uint(tileCoord.y * tileGridSize.x + tileCoord.x);

    float tileDepth = tileConservativeDepth[tileIndex];
    if (tileDepth <= vanillaDepth) {
        return;
    }

    vec3 rayOrigin = frame.cameraPos.xyz;
    vec3 rayDir = computeRayDirection(pixel, frame.invViewProj);

    uint localChunkCount = min(tileChunkCount[tileIndex], MAX_CHUNKS_PER_TILE);

    HitResult best;
    best.hit = false;
    best.t = 1e30;

    for (uint i = 0u; i < localChunkCount; i++) {
        uint globalChunkIndex = tileChunkIndices[tileIndex * MAX_CHUNKS_PER_TILE + i];
        ChunkEntry c = chunks[globalChunkIndex];
        vec3 origin = vec3(c.originX, c.originY, c.originZ);

        HitResult hit = traverseDag(c.rootIndex, rayOrigin, rayDir, origin, c.size, best.t);
        if (hit.hit && (!best.hit || hit.t < best.t)) {
            best = hit;
        }
    }

    if (!best.hit) {
        return;
    }

    vec3 worldPos = rayOrigin + rayDir * best.t;
    vec3 relativeToCamera = worldPos - frame.cameraPos.xyz;
    vec4 clipPos = frame.viewProj * vec4(relativeToCamera, 1.0);
    float chunkDepth = clipPos.z / clipPos.w;

    if (chunkDepth > vanillaDepth) {
        vec4 color = colors[best.blockId];
        float lighting = 0.6 + 0.4 * max(dot(best.normal, normalize(vec3(0.4, 1.0, 0.3))), 0.0);
        imageStore(outputColor, pixel, vec4(color.rgb * lighting, 1.0));
    }
}
