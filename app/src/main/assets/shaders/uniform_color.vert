uniform mat4 viewProjectionMatrix;
uniform mat4 modelMatrix;
attribute vec4 position;

varying vec2 textureCoords;

void main()
{
	gl_Position = viewProjectionMatrix * (modelMatrix * position);
}