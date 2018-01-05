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

#ifndef EXPORTS_RSH
#define EXPORTS_RSH

short *audio_buffer;
uchar *value_buffer;
uchar4 *pixel_buffer;
uchar4 *spectrum_buffer;
uchar4 *spectrogram_buffer;
uchar4 *saved_buffer;
float *volume;
int *status;
#define current_mode (&status[0])
#define free_running (status[1])
#define saved_width (&status[2])
#define saved_height (&status[3])

#endif