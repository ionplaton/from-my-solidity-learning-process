//SPDX-License_Identifier: MIT
pragma solidity ^0.8.14

contract myLoopingPracticeContract {

// Create a list that ranges from 1 to 20 and anothe list with following numbers: 1, 4, 34, 56.
    uint [] longList = [1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20];
    uint [] numbersList= [1, 4, 34, 56];

/* Create a function that loops through numbersList and return a true value if the number that 
the user inputs exists in the list, otherwise it should return false
*/
    function numbersListLoop(uint userNumber) public view returns(bool) {
        bool numberExists = false;
        for(uint i = 0; i < numbersList.length; i++) {
           if(numbersList[i] == userNumber) {
               numberExists = true;
           }
        } return numberExists;
    }

// Create a function that loops through and returns how many even numbers there are in the longList.
    function evenNumbersLoop() public view returns (uint) {
        uint count = 0;
        for(uint i = 0; i < longList.length; i++) {
            if(longList[i] % 2 == 0) {
                count++;
            }
        }
        return count;
    }
}
