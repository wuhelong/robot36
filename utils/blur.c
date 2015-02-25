/*
Copyright 2014 Ahmet Inan <xdsopl@googlemail.com>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

#include <math.h>
#include <stdio.h>

double gauss(double x, double radius)
{
	double sigma = radius / 3.0;
	return radius ? exp(- x * x / (2.0 * sigma * sigma)) / sqrt(2.0 * M_PI * sigma * sigma) : 1.0;
}
void emit(int radius)
{
	printf("\t\tif ((p-%d) < begin || end <= (p+%d) || (i-%d) < 0 || buffer_length <= (i+%d)) {\n", radius, radius, radius, radius);
	for (int i = -radius; i < 0; ++i) {
		printf("\t\t\tif (begin <= (p%d)) {\n", i);
		printf("\t\t\t\tweight_sum += %d;\n",
			(int)(16384 * gauss(i, radius)));
		printf("\t\t\t\tvalue_sum += %d * value_buffer[(i%d)&buffer_mask];\n",
			(int)(16384 * gauss(i, radius)), i);
		printf("\t\t\t}\n");
	}
	for (int i = 0; i <= radius; ++i) {
		printf("\t\t\tif ((p+%d) < end) {\n", i);
		printf("\t\t\t\tweight_sum += %d;\n",
			(int)(16384 * gauss(i, radius)));
		printf("\t\t\t\tvalue_sum += %d * value_buffer[(i+%d)&buffer_mask];\n",
			(int)(16384 * gauss(i, radius)), i);
		printf("\t\t\t}\n");
	}
	printf("\t\t\treturn value_sum / weight_sum;\n\t\t}\n");
	int sum = 0;
	for (int i = -radius; i <= radius; ++i)
		sum += 16384 * gauss(i, radius);
	int factor = (16384 * 16384) / (sum + 1);
	for (int i = -radius; i <= radius; ++i)
		printf("\t\t%s%d * value_buffer[i%s%d]%s\n",
			i != -radius ? "\t" : "return (",
			(int)(factor * gauss(i, radius)),
			i < 0 ? "" : "+",
			i,
			i != radius ? " +" : ") >> 14;"
		);
}
int main()
{
	printf("/* code generated by 'utils/blur.c' */\n");
	printf("static uchar value_blur(int pixel, int begin, int end)\n{\n");
	printf("\tint p = (pixel * (end - begin) + (end - begin) / 2) / bitmap_width + begin;\n");
	printf("\tint i = p & buffer_mask;\n");
	printf("\tint weight_sum = 0;\n");
	printf("\tint value_sum = 0;\n");
	printf("\tswitch (max(0, blur_power + user_blur)) {\n");
	int max_power = 6;
	for (int i = 0; i <= max_power; ++i) {
		printf("\tcase %d:\n", i);
		if (i == max_power)
			printf("\tdefault:\n");
		emit((1 << i) | 1);
	}
	printf("\t}\n\treturn 0;\n}\n");
	return 0;
}
