FROM teamblockchain/corda-node:1.0.0

# Set image labels
LABEL vendor="Luxoft"
MAINTAINER Alexey Koren <akoren@luxoft.com>


# Copy project-specific stuff to working directory
#COPY certificates certificates
COPY plugins plugins
COPY node.conf .
#COPY corda-webserver.jar .

# Start runit
CMD ["/sbin/my_init"]