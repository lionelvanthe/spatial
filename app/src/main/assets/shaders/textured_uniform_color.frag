precision highp float;

uniform sampler2D sampler;
uniform vec4 color;

varying vec2 textureCoords;

void main()
{
	vec4 textureColor = texture2D(sampler, textureCoords);

	gl_FragColor = vec4((color.a * color.rgb) + (1.0 - color.a) * textureColor.rgb, 1.0);
}