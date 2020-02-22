#pragma once
#include <Arduino.h>

#define WHEEL_WIDTH 179

class Position {
    public:
        Position(int*, int*, float);
        void compute();
        void reset();
        float getX();
        float getY();
        int getHeading();

    private:
        int *ticksLeft;
        int *ticksRight;
        float ticksPerMilli;
        float x;
        float y;
        float heading;
        int headingDegree;
        float deltaLeft;
        float deltaRight;
        int boundAngle(int);
};