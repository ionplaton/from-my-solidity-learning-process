//SPDX-License-Identifier: MIT
pragma solidity ^0.8.18;

contract RestrictedAccess {

// We will set the owner to be the owner of the contract (msg.sender)
    address public owner = msg.sender;

/*
We want to restrict the address of the changeOwner function. We don't want anyone to be able to change 
the owner address. We only want the current owner to change the owner's address. To do this we can use 
a modifier - onlyBy. We will name it onlyBy and it will take as input an address. The modifier have a 
requirement that the msg.sender should be equal to the input of the _account. 
If it fails, then we can write a statement "Sender not authorized!" 
*/
    modifier onlyBy(address _account) { 
        require(msg.sender == _account,
        'Sender not authorized!'
        );
        _;
    }
    
// The function will take an address as an argument and call it _owner, and It should return owner = _owner. 
    function changeOwner(address _owner) onlyBy(owner) public {
        owner = _owner;
    }
}
