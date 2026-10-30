precision highp float;

uniform sampler2D sampler;

varying vec2 textureCoords;
varying vec4 color;
        
void main()
{
	vec4 textureColor = texture2D(sampler, textureCoords);
	
	gl_FragColor = vec4(color.r * textureColor.r, color.g * textureColor.g, color.b * textureColor.b, color.a * textureColor.a);
}