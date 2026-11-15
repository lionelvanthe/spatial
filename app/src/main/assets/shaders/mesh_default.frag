precision highp float;

uniform sampler2D uSampler;
varying vec2 vTextureCoords;

void main() {
    gl_FragColor = texture2D(uSampler, vTextureCoords);
}
