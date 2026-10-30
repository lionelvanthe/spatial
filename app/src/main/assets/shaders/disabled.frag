#define ALPHA_FACTOR 0.3

precision highp float;

uniform sampler2D sampler;

varying vec2 textureCoords;
        
void main()
{
	vec4 textureColor = texture2D(sampler, textureCoords);
	
	float color = ((textureColor.r + textureColor.g + textureColor.b) / 3.0) * ALPHA_FACTOR;
	
	gl_FragColor = vec4(color, color, color, textureColor.a * ALPHA_FACTOR);
}