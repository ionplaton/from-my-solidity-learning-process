//SPDX-License-Identifier: MIT
pragma solidity ^0.8.18;

contract RestrictedAccess {

/*
I will create a contract called RestrictedAccess with two variables owner and creationTime, 
and I am going to initialize them. I will set the owner to be the owner of the contract (msg.sender) 
and for the creationTime I am going to use a global variable (block.timestamp).
*/
    address public owner = msg.sender;
    uint public creationTime = block.timestamp;

/*
I will create a new modifier (onlyAfter) which will take an uint as input and will name it _time. 
For the require I will write that block.timestamp should be >= the amount of _time, 
otherwise, if it doesn't run, I will receive the message 'Function was called too early!'.
*/
    modifier onlyAfter(uint _time) {
        require(block.timestamp >= _time,
        'Function was called too early!');
        _;
    }

//I will write a function to disown the owner after 3 weeks from the contract creation time
    function disown() onlyBy(owner) onlyAfter(creationTime + 3 weeks) public {
        delete owner;
    }
}

