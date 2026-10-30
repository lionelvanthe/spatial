uniform mat4 viewProjectionMatrix;
uniform mat4 modelMatrix;
attribute vec4 position;
attribute vec4 colorRGBA;

varying vec4 color;

void main()
{
	gl_Position = viewProjectionMatrix * (modelMatrix * position);
	color = colorRGBA;
}