uniform mat4 viewProjectionMatrix;
uniform mat4 modelMatrix;
attribute vec4 position;
attribute vec2 textureST;

varying vec2 textureCoords;

void main()
{
	gl_Position = viewProjectionMatrix * (modelMatrix * position);
	textureCoords = textureST;
	gl_PointSize = 3.0;
}