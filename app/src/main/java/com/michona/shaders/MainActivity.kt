package com.michona.shaders

import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.michona.shaders.ui.theme.ShadersplaygroundTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SHADER_SRC = """
    uniform shader composable;
    uniform float2 u_res;
    uniform float u_time;
    
    float op_smooth_union( float d1, float d2, float k )
    {
        float h = clamp( 0.5 + 0.5*(d2-d1)/k, 0.0, 1.0 );
        return mix( d2, d1, h ) - k*h*(1.0-h);
    }
    
    half3 update_saturation(half3 color, float saturation) 
    {
        return mix(half3(dot(color.rgb, half3(0.299, 0.587, 0.114))), color.rgb, saturation);
    }
    half3 mix_colors(half3 color_a, half3 color_b)
    {
        return mix(color_a, color_b, 0.6);
    }
    
    float sd_round_box(float3 p, float3 b, float r)
    {
        float3 d = abs(p) - b;
        return min(max(d.x,max(d.y,d.z)),0.0) + length(max(d,0.0)) - r;
    }
    
    float distance_from_sphere(float3 p, float r)
    {
        return length(p) - r;
    }
    
    // TODO: ? how
    float aa_mask(float x) 
    {
        return 1.0 - smoothstep(0.0, 1.0, x);
    }
    
    float random (float2 st) 
    {
        return fract(sin(dot(st.xy, float2(12.9898,78.233))) * 43758.5453123);
    }

    // https://www.shadertoy.com/view/4dS3Wd
    float noise (float2 st) 
    {
        float2 i = floor(st);
        float2 f = fract(st);

        // Four corners in 2D of a tile
        float a = random(i);
        float b = random(i + float2(1.0, 0.0));
        float c = random(i + float2(0.0, 1.0));
        float d = random(i + float2(1.0, 1.0));

        // Smooth Interpolation

        // Cubic Hermine Curve.  Same as SmoothStep()
        float2 u = f*f*(3.0-2.0*f);
        // u = smoothstep(0.,1.,f);

        // Mix 4 coorners percentages
        return mix(a, b, u.x) +
            (c - a)* u.y * (1.0 - u.x) +
            (d - b) * u.x * u.y;
    }
    
    float3 p_bend(float3 p)
    {
        float k = 5.0;
        float c = cos(k*p.x);
        float s = sin(k*p.x);
        mat2  m = mat2(c,-s,s,c); 
        return float3(m*p.xy,p.z); 
    }
    
    float3 p_twist(float3 p)
    {
        const float k = 1.0;
        float c = cos(k*p.y);
        float s = sin(k*p.y);
        mat2  m = mat2(c,-s,s,c);
        return float3(m*p.xz,p.y); 
    }

    float map_the_world(float3 p)
    {
        float3 q = p;
        //q = p - clamp(p, -0.2, 0.2);
        //q = p_bend(q);
        //q = p_bend(q);
        
        float displacement_sin = sin(5.0 * p.x) * sin(5.0 * p.y) * sin(5.0 * p.z) * 0.25;
        float displacement = noise(p.xy + float2( u_time * 0.2));
        //float displacement = noise(displacement_sin.x + + float2( u_time * 0.2));


        float sphere_0 = distance_from_sphere(q, 1.0);
        float sphere_1 = distance_from_sphere(q + float3(0.5, 0.0, 0.0), 1.0);
        float box_0 = sd_round_box(q, vec3(0.2,0.4,0.5), 0.1);

        //return op_smooth_union(sphere_0, sphere_1, 0.1);
        return sphere_0 + displacement;
        //return box_0;
    }
    
    float3 calculate_normal(float3 p)
    {
        // swizzling - look into it more? 
        const float3 small_step = float3(0.001, 0.0, 0.0);

        float gradient_x = map_the_world(p + small_step.xyy) - map_the_world(p - small_step.xyy);
        float gradient_y = map_the_world(p + small_step.yxy) - map_the_world(p - small_step.yxy);
        float gradient_z = map_the_world(p + small_step.yyx) - map_the_world(p - small_step.yyx);

        float3 normal = float3(gradient_x, gradient_y, gradient_z);

        return normalize(normal);
    }

    float3 ray_march(float3 ro, float3 rd)
    {
        float total_distance_traveled = 0.0;
        const float MINIMUM_HIT_DISTANCE = 0.001;
        const float MAXIMUM_TRACE_DISTANCE = 1000.0;
        // Separate colors that are close to us! - Increase the saturation?
        const float SLICE_DISTANCE_THRESHOLD = 1.2;

        for (int i = 0; i < 32; ++i)
        {
            float3 current_position = ro + total_distance_traveled * rd;

            float distance_to_closest = map_the_world(current_position);

            if (distance_to_closest < MINIMUM_HIT_DISTANCE) 
            {
                float3 normal = calculate_normal(current_position);
                
                // TODO: think about where the light source should be? Should it move?
                // TODO: do we need diffuse?
                float3 light_position = float3(2.0, -5.0, 5.0);
                
                // Calculate the unit direction vector that points from
                // the point of intersection to the light source
                float3 direction_to_light = normalize(current_position - light_position);

                float diffuse_intensity = max(0.0, dot(normal, direction_to_light));

                
                half3 base_color = half3(1.0, 0.0, 0.1);
                
                // Desaturate colors that are futher away - maybe?
                //if (total_distance_traveled > SLICE_DISTANCE_THRESHOLD) {
                //    base_color = update_saturation(base_color, 0.5);
                // }
                
                return base_color * diffuse_intensity;
                //return base_color;
                //return normal * 0.5 + 0.5;
                //return float3(1.0, 0.0, 0.0);
            } 

            if (total_distance_traveled > MAXIMUM_TRACE_DISTANCE)
            {
                break;
            }
            total_distance_traveled += distance_to_closest;
        }
        // TODO: need to apply blur here?
        return half3(0.0, 0.2, 0.3);
        //return update_saturation(half3(0.0, 1.0, 0.0), 0.5);
    }
    
    half4 main(float2 fragCoord) {
        float2 uv = (fragCoord / u_res.xy) * 2.0  - 1.0;
        
        float3 camera_position = float3(0.0, 0.0, -2.0);
        float3 ro = camera_position;
        float3 rd = float3(uv, 1.0);
        
        float3 shaded_color = ray_march(ro, rd);
        
        //return half4(mix_colors(half3(0.149,0.141,0.912), half3(0.2,0.833,0.224)), 1.0);
        return half4(shaded_color, 1.0);
        //return half4(uv.xy, 0.0, 1.0);
    }
"""

class MainActivity : ComponentActivity()    {
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val photo = BitmapFactory.decodeResource(
            resources,
            R.drawable.ic_test
        )
        val shader = RuntimeShader(SHADER_SRC)

        setContent {
            val scope = rememberCoroutineScope()
            val timeMs = remember {
                mutableFloatStateOf(0f)
            }

            LaunchedEffect(key1 = Unit) {
                scope.launch {
                    while (true) {
                        // TODO: this is dumb -.-
                        timeMs.floatValue += 20f / 1000f
                        timeMs.floatValue %= 10000f
                        delay(20)
                    }
                }
            }
            ShadersplaygroundTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Image(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(300.dp)
                                .onSizeChanged { size ->
                                    shader.setFloatUniform(
                                        "u_res",
                                        size.width.toFloat(),
                                        size.height.toFloat()
                                    )
                                }
                                .graphicsLayer {
                                    clip = true
                                    shader.setFloatUniform(
                                        "u_time",
                                        timeMs.floatValue
                                    )
                                    renderEffect =
                                        RenderEffect
                                            .createRuntimeShaderEffect(shader, "composable")
                                            .asComposeRenderEffect()
                                },
                            bitmap = photo.asImageBitmap(),
                            contentDescription = ""
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    ShadersplaygroundTheme {
        Greeting("Android")
    }
}