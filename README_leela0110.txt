To use Leela 0.11.0 instead of Leela Zero on Lizzie, replace the file
"leelaz" with a wrapped leela 0.11.0 that ignores unknown options.
I use the following shell script as "leelaz".

#!/bin/sh
leela_gtp -g
