uniform mat4 uViewProjectionMatrix;
uniform mat4 uModelMatrix;

attribute vec3 aPosition;
attribute vec2 aTextureST;

varying vec2 vTextureCoords;

void main() {
    gl_Position = uViewProjectionMatrix * (uModelMatrix * vec4(aPosition, 1.0));
    vTextureCoords = aTextureST;
}
